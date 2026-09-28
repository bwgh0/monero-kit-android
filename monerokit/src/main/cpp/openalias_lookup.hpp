// openalias_lookup.hpp: DNSSEC-validating TXT lookup for OpenAlias names.
// MoneroKit.Swift (Sources/CMonero) and monero-kit-android
// (monerokit/src/main/cpp) hold identical copies of this file.
#pragma once

#include <string>

namespace openalias {

// Looks up the TXT records of `name` through a DNS-over-TCP forwarder on the
// IPv4 loopback at `forwarder_port`, and validates DNSSEC from the root.
// Blocks for at most `timeout_ms`. Returns one ASCII JSON object (see the
// OpenAlias send spec, section 3.4). Never throws.
std::string lookup_txt_json(const std::string& name, int forwarder_port, int timeout_ms);

}  // namespace openalias
