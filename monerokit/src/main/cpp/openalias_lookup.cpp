// openalias_lookup.cpp: see openalias_lookup.hpp.
#include "openalias_lookup.hpp"

#include "unbound.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

namespace {

// Root KSK-2017 (20326) and KSK-2024 (38696). KSK-2024 alone signs the root
// DNSKEY set from 2026-10-11. Source: IANA root-anchors.xml.
const char* const kRootAnchors[] = {
    ". IN DS 20326 8 2 E06D44B80B8F1D39A95C0B0D7C65D08458E880409BBC683457104237C7F8EC8D",
    ". IN DS 38696 8 2 683D2D0ACB8C9B712A1948B27F741219298D0A450D612C483AF444A4C0FB2B16",
};

struct Outcome {
    bool done = false;
    int err = 0;
    int rcode = 0;
    bool havedata = false;
    bool nxdomain = false;
    bool secure = false;
    bool bogus = false;
    std::string why_bogus;
    std::vector<std::string> records_hex;
};

std::string to_hex(const char* data, int len) {
    static const char digits[] = "0123456789abcdef";
    std::string out;
    out.reserve(static_cast<size_t>(len) * 2);
    for (int i = 0; i < len; ++i) {
        const unsigned char c = static_cast<unsigned char>(data[i]);
        out.push_back(digits[c >> 4]);
        out.push_back(digits[c & 0x0f]);
    }
    return out;
}

std::string json_escape(const std::string& s) {
    std::string out;
    for (size_t i = 0; i < s.size(); ++i) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        if (c == '"') {
            out += "\\\"";
        } else if (c == '\\') {
            out += "\\\\";
        } else if (c >= 0x80) {
            out.push_back('?');  // ASCII-only JSON: safe for JNI NewStringUTF
        } else if (c < 0x20) {
            char buf[8];
            snprintf(buf, sizeof buf, "\\u%04x", c);
            out += buf;
        } else {
            out.push_back(static_cast<char>(c));
        }
    }
    return out;
}

std::string error_json(const std::string& message) {
    return "{\"status\":\"error\",\"error\":\"" + json_escape(message) + "\"}";
}

const char* flag(bool b) { return b ? "true" : "false"; }

void on_result(void* arg, int err, struct ub_result* result) {
    Outcome* o = static_cast<Outcome*>(arg);
    o->done = true;
    o->err = err;
    if (err != 0 || result == nullptr) {
        if (result != nullptr) ub_resolve_free(result);
        return;
    }
    o->rcode = result->rcode;
    o->havedata = result->havedata != 0;
    o->nxdomain = result->nxdomain != 0;
    o->secure = result->secure != 0;
    o->bogus = result->bogus != 0;
    if (result->why_bogus != nullptr) o->why_bogus = result->why_bogus;
    // A bogus answer is never handed on: no records leave the kit.
    if (result->havedata != 0 && result->bogus == 0 && result->data != nullptr) {
        for (int i = 0; result->data[i] != nullptr; ++i) {
            o->records_hex.push_back(to_hex(result->data[i], result->len[i]));
        }
    }
    ub_resolve_free(result);
}

// "<IPv4 loopback>@<port>", built from INADDR_LOOPBACK so no address
// literal appears in the source.
std::string loopback_forwarder(int port) {
    char host[INET_ADDRSTRLEN] = {0};
    struct in_addr lo;
    lo.s_addr = htonl(INADDR_LOOPBACK);
    if (inet_ntop(AF_INET, &lo, host, sizeof host) == nullptr) return std::string();
    return std::string(host) + "@" + std::to_string(port);
}

struct CtxGuard {
    struct ub_ctx* ctx;
    ~CtxGuard() { if (ctx != nullptr) ub_ctx_delete(ctx); }
};

}  // namespace

namespace openalias {

std::string lookup_txt_json(const std::string& name, int forwarder_port, int timeout_ms) {
    try {
        if (name.empty() || name.size() > 253) return error_json("bad name");
        if (forwarder_port < 1 || forwarder_port > 65535) return error_json("bad port");
        if (timeout_ms < 1000 || timeout_ms > 60000) return error_json("bad timeout");

        CtxGuard guard = {ub_ctx_create()};
        if (guard.ctx == nullptr) return error_json("ub_ctx_create failed");
        struct ub_ctx* ctx = guard.ctx;

        const std::string fwd = loopback_forwarder(forwarder_port);
        if (fwd.empty()) return error_json("no loopback address");

        // Order matters: every option before the first resolve.
        int rc = ub_ctx_async(ctx, 1);  // a thread, never fork()
        if (rc == 0) rc = ub_ctx_set_option(ctx, "do-udp:", "no");
        if (rc == 0) rc = ub_ctx_set_option(ctx, "tcp-upstream:", "yes");
        if (rc == 0) rc = ub_ctx_set_option(ctx, "val-log-level:", "2");  // fills why_bogus
        if (rc == 0) rc = ub_ctx_set_option(ctx, "val-log-squelch:", "yes");  // no name in logs
        if (rc == 0) rc = ub_ctx_set_fwd(ctx, fwd.c_str());
        for (size_t i = 0; rc == 0 && i < sizeof kRootAnchors / sizeof kRootAnchors[0]; ++i) {
            rc = ub_ctx_add_ta(ctx, kRootAnchors[i]);
        }
        if (rc != 0) return error_json(ub_strerror(rc));

        Outcome outcome;
        int async_id = 0;
        rc = ub_resolve_async(ctx, name.c_str(), 16 /* TXT */, 1 /* IN */, &outcome, on_result, &async_id);
        if (rc != 0) return error_json(ub_strerror(rc));

        const int fd = ub_fd(ctx);
        const std::chrono::steady_clock::time_point deadline =
            std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
        while (!outcome.done) {
            const long long left = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now()).count();
            if (left <= 0) {
                ub_cancel(ctx, async_id);
                return "{\"status\":\"timeout\"}";
            }
            struct pollfd p;
            p.fd = fd;
            p.events = POLLIN;
            p.revents = 0;
            const int n = poll(&p, 1, static_cast<int>(left));
            if (n < 0 && errno != EINTR) {
                ub_cancel(ctx, async_id);
                return error_json("poll failed");
            }
            if (n > 0) {
                rc = ub_process(ctx);
                if (rc != 0) return error_json(ub_strerror(rc));
            }
        }
        if (outcome.err != 0) return error_json(ub_strerror(outcome.err));

        std::string json = "{\"status\":\"ok\",\"rcode\":" + std::to_string(outcome.rcode);
        json += ",\"havedata\":" + std::string(flag(outcome.havedata));
        json += ",\"nxdomain\":" + std::string(flag(outcome.nxdomain));
        json += ",\"secure\":" + std::string(flag(outcome.secure));
        json += ",\"bogus\":" + std::string(flag(outcome.bogus));
        json += ",\"why_bogus\":\"" + json_escape(outcome.why_bogus) + "\"";
        json += ",\"records\":[";
        for (size_t i = 0; i < outcome.records_hex.size(); ++i) {
            if (i > 0) json += ",";
            json += "\"" + outcome.records_hex[i] + "\"";
        }
        json += "]}";
        return json;
    } catch (...) {
        return error_json("exception");
    }
}

}  // namespace openalias
