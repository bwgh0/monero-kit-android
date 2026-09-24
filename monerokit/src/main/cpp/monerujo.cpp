/**
 * Copyright (c) 2017-2024 m2049r
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <inttypes.h>
#include "monerujo.h"
#include "wallet2_api.h"
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <cerrno>
#include <dirent.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/stat.h>

#ifdef __cplusplus
extern "C"
{
#endif

#include <android/log.h>
#define LOG_TAG "WalletNDK"
#define LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG,__VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG  , LOG_TAG,__VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO   , LOG_TAG,__VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN   , LOG_TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR  , LOG_TAG,__VA_ARGS__)

static JavaVM *cachedJVM;
static jclass class_ArrayList;
static jclass class_WalletListener;
static jclass class_CoinsInfo;
static jclass class_TransactionInfo;
static jclass class_Transfer;
static jclass class_Ledger;
static jclass class_WalletStatus;
static jclass class_BluetoothService;
//static jclass class_SidekickService;

std::mutex _listenerMutex;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    cachedJVM = jvm;
    LOGI("JNI_OnLoad");
    JNIEnv *jenv;
    if (jvm->GetEnv(reinterpret_cast<void **>(&jenv), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }
    //LOGI("JNI_OnLoad ok");

    class_ArrayList = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("java/util/ArrayList")));
    class_CoinsInfo = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("io/horizontalsystems/monerokit/model/CoinsInfo")));
    class_TransactionInfo = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("io/horizontalsystems/monerokit/model/TransactionInfo")));
    class_Transfer = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("io/horizontalsystems/monerokit/model/Transfer")));
    class_WalletListener = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("io/horizontalsystems/monerokit/model/WalletListener")));
    class_Ledger = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("io/horizontalsystems/monerokit/ledger/Ledger")));
    class_WalletStatus = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("io/horizontalsystems/monerokit/model/Wallet$Status")));
    class_BluetoothService = static_cast<jclass>(jenv->NewGlobalRef(
            jenv->FindClass("io/horizontalsystems/monerokit/service/BluetoothService")));
    return JNI_VERSION_1_6;
}
#ifdef __cplusplus
}
#endif

int attachJVM(JNIEnv **jenv) {
    int envStat = cachedJVM->GetEnv((void **) jenv, JNI_VERSION_1_6);
    if (envStat == JNI_EDETACHED) {
        if (cachedJVM->AttachCurrentThread(jenv, nullptr) != 0) {
            LOGE("Failed to attach");
            return JNI_ERR;
        }
    } else if (envStat == JNI_EVERSION) {
        LOGE("GetEnv: version not supported");
        return JNI_ERR;
    }
    //LOGI("envStat=%i", envStat);
    return envStat;
}

void detachJVM(JNIEnv *jenv, int envStat) {
    //LOGI("envStat=%i", envStat);
    if (jenv->ExceptionCheck()) {
        jenv->ExceptionDescribe();
    }

    if (envStat == JNI_EDETACHED) {
        cachedJVM->DetachCurrentThread();
    }
}

struct MyWalletListener : Monero::WalletListener {
    jobject jlistener;

    MyWalletListener(JNIEnv *env, jobject aListener) {
        LOGD("Created MyListener");
        jlistener = env->NewGlobalRef(aListener);;
    }

    ~MyWalletListener() {
        LOGD("Destroyed MyListener");
    };

    void deleteGlobalJavaRef(JNIEnv *env) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        env->DeleteGlobalRef(jlistener);
        jlistener = nullptr;
    }

    /**
 * @brief updated  - generic callback, called when any event (sent/received/block reveived/etc) happened with the wallet;
 */
    void updated() {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("updated");
        JNIEnv *jenv;
        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jmethodID listenerClass_updated = jenv->GetMethodID(class_WalletListener, "updated", "()V");
        jenv->CallVoidMethod(jlistener, listenerClass_updated);

        detachJVM(jenv, envStat);
    }


    /**
     * @brief moneySpent - called when money spent
     * @param txId       - transaction id
     * @param amount     - amount
     */
    void moneySpent(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("moneySpent %"
                     PRIu64, amount);
    }

    /**
     * @brief moneyReceived - called when money received
     * @param txId          - transaction id
     * @param amount        - amount
     */
    void moneyReceived(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("moneyReceived %"
                     PRIu64, amount);
    }

    /**
     * @brief unconfirmedMoneyReceived - called when payment arrived in tx pool
     * @param txId          - transaction id
     * @param amount        - amount
     */
    void unconfirmedMoneyReceived(const std::string &txId, uint64_t amount) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("unconfirmedMoneyReceived %"
                     PRIu64, amount);
    }

    /**
     * @brief newBlock      - called when new block received
     * @param height        - block height
     */
    void newBlock(uint64_t height) {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        //LOGD("newBlock");
        JNIEnv *jenv;
        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jlong h = static_cast<jlong>(height);
        jmethodID listenerClass_newBlock = jenv->GetMethodID(class_WalletListener, "newBlock",
                                                             "(J)V");
        jenv->CallVoidMethod(jlistener, listenerClass_newBlock, h);

        detachJVM(jenv, envStat);
    }

/**
 * @brief refreshed - called when wallet refreshed by background thread or explicitly refreshed by calling "refresh" synchronously
 */
    void refreshed() {
        std::lock_guard<std::mutex> lock(_listenerMutex);
        if (jlistener == nullptr) return;
        LOGD("refreshed");
        JNIEnv *jenv;

        int envStat = attachJVM(&jenv);
        if (envStat == JNI_ERR) return;

        jmethodID listenerClass_refreshed = jenv->GetMethodID(class_WalletListener, "refreshed",
                                                              "()V");
        jenv->CallVoidMethod(jlistener, listenerClass_refreshed);
        detachJVM(jenv, envStat);
    }
};


//// helper methods
std::vector<std::string> java2cpp(JNIEnv *env, jobject arrayList) {

    jmethodID java_util_ArrayList_size = env->GetMethodID(class_ArrayList, "size", "()I");
    jmethodID java_util_ArrayList_get = env->GetMethodID(class_ArrayList, "get",
                                                         "(I)Ljava/lang/Object;");

    jint len = env->CallIntMethod(arrayList, java_util_ArrayList_size);
    std::vector<std::string> result;
    result.reserve(len);
    for (jint i = 0; i < len; i++) {
        jstring element = static_cast<jstring>(env->CallObjectMethod(arrayList,
                                                                     java_util_ArrayList_get, i));
        const char *pchars =
                (element != nullptr) ? env->GetStringUTFChars(element, nullptr) : nullptr;
        if (pchars == nullptr) {
            result.clear();
            return result;
        }
        result.emplace_back(pchars);
        env->ReleaseStringUTFChars(element, pchars);
        env->DeleteLocalRef(element);
    }
    return result;
}

jobject cpp2java(JNIEnv *env, const std::vector<std::string> &vector) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject result = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                    static_cast<jint> (vector.size()));
    for (const std::string &s: vector) {
        jstring element = env->NewStringUTF(s.c_str());
        env->CallBooleanMethod(result, java_util_ArrayList_add, element);
        env->DeleteLocalRef(element);
    }
    return result;
}

/// end helpers

#ifdef __cplusplus
extern "C"
{
#endif


/**********************************/
/********** WalletManager *********/
/**********************************/
JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_createWalletJ(JNIEnv *env, jobject instance,
                                                            jstring path, jstring password,
                                                            jstring language,
                                                            jint networkType) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    if (_path == nullptr) return 0;
    const char *_password = env->GetStringUTFChars(password, nullptr);
    if (_password == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        return 0;
    }
    const char *_language = env->GetStringUTFChars(language, nullptr);
    if (_language == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        return 0;
    }
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWallet(
                    std::string(_path),
                    std::string(_password),
                    std::string(_language),
                    _networkType);

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    env->ReleaseStringUTFChars(language, _language);
    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_openWalletJ(JNIEnv *env, jobject instance,
                                                          jstring path, jstring password,
                                                          jint networkType) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    if (_path == nullptr) return 0;
    const char *_password = env->GetStringUTFChars(password, nullptr);
    if (_password == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        return 0;
    }
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->openWallet(
                    std::string(_path),
                    std::string(_password),
                    _networkType);

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    return reinterpret_cast<jlong>(wallet);
}
extern "C"
JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_recoveryWalletJ(
        JNIEnv *env, jobject instance,
        jstring path, jstring password,
        jstring mnemonic, jstring offset,
        jint networkType,
        jlong restoreHeight) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    if (_path == nullptr) return 0;
    const char *_password = env->GetStringUTFChars(password, nullptr);
    if (_password == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        return 0;
    }
    const char *_mnemonic = env->GetStringUTFChars(mnemonic, nullptr);
    if (_mnemonic == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        return 0;
    }
    const char *_offset = env->GetStringUTFChars(offset, nullptr);
    if (_offset == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        env->ReleaseStringUTFChars(mnemonic, _mnemonic);
        return 0;
    }
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->recoveryWallet(
                    std::string(_path),
                    std::string(_password),
                    std::string(_mnemonic),
                    _networkType,
                    (uint64_t) restoreHeight,
                    1, // kdf_rounds
                    std::string(_offset));

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    env->ReleaseStringUTFChars(mnemonic, _mnemonic);
    env->ReleaseStringUTFChars(offset, _offset);
    return reinterpret_cast<jlong>(wallet);

}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_createWalletFromKeysJ(JNIEnv *env, jobject instance,
                                                                    jstring path, jstring password,
                                                                    jstring language,
                                                                    jint networkType,
                                                                    jlong restoreHeight,
                                                                    jstring addressString,
                                                                    jstring viewKeyString,
                                                                    jstring spendKeyString) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    if (_path == nullptr) return 0;
    const char *_password = env->GetStringUTFChars(password, nullptr);
    if (_password == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        return 0;
    }
    const char *_language = env->GetStringUTFChars(language, nullptr);
    if (_language == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        return 0;
    }
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    const char *_addressString = env->GetStringUTFChars(addressString, nullptr);
    if (_addressString == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        env->ReleaseStringUTFChars(language, _language);
        return 0;
    }
    const char *_viewKeyString = env->GetStringUTFChars(viewKeyString, nullptr);
    if (_viewKeyString == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        env->ReleaseStringUTFChars(language, _language);
        env->ReleaseStringUTFChars(addressString, _addressString);
        return 0;
    }
    const char *_spendKeyString = env->GetStringUTFChars(spendKeyString, nullptr);
    if (_spendKeyString == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        env->ReleaseStringUTFChars(language, _language);
        env->ReleaseStringUTFChars(addressString, _addressString);
        env->ReleaseStringUTFChars(viewKeyString, _viewKeyString);
        return 0;
    }

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWalletFromKeys(
                    std::string(_path),
                    std::string(_password),
                    std::string(_language),
                    _networkType,
                    (uint64_t) restoreHeight,
                    std::string(_addressString),
                    std::string(_viewKeyString),
                    std::string(_spendKeyString));

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    env->ReleaseStringUTFChars(language, _language);
    env->ReleaseStringUTFChars(addressString, _addressString);
    env->ReleaseStringUTFChars(viewKeyString, _viewKeyString);
    env->ReleaseStringUTFChars(spendKeyString, _spendKeyString);
    return reinterpret_cast<jlong>(wallet);
}


// virtual void setSubaddressLookahead(uint32_t major, uint32_t minor) = 0;

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_createWalletFromDeviceJ(JNIEnv *env, jobject instance,
                                                                      jstring path,
                                                                      jstring password,
                                                                      jint networkType,
                                                                      jstring deviceName,
                                                                      jlong restoreHeight,
                                                                      jstring subaddressLookahead) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    if (_path == nullptr) return 0;
    const char *_password = env->GetStringUTFChars(password, nullptr);
    if (_password == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        return 0;
    }
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    const char *_deviceName = env->GetStringUTFChars(deviceName, nullptr);
    if (_deviceName == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        return 0;
    }
    const char *_subaddressLookahead = env->GetStringUTFChars(subaddressLookahead, nullptr);
    if (_subaddressLookahead == nullptr) {
        env->ReleaseStringUTFChars(path, _path);
        env->ReleaseStringUTFChars(password, _password);
        env->ReleaseStringUTFChars(deviceName, _deviceName);
        return 0;
    }

    Monero::Wallet *wallet =
            Monero::WalletManagerFactory::getWalletManager()->createWalletFromDevice(
                    std::string(_path),
                    std::string(_password),
                    _networkType,
                    std::string(_deviceName),
                    (uint64_t) restoreHeight,
                    std::string(_subaddressLookahead));

    env->ReleaseStringUTFChars(path, _path);
    env->ReleaseStringUTFChars(password, _password);
    env->ReleaseStringUTFChars(deviceName, _deviceName);
    env->ReleaseStringUTFChars(subaddressLookahead, _subaddressLookahead);
    return reinterpret_cast<jlong>(wallet);
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_walletExists(JNIEnv *env, jobject instance,
                                                           jstring path) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    if (_path == nullptr) return JNI_FALSE;
    bool exists =
            Monero::WalletManagerFactory::getWalletManager()->walletExists(std::string(_path));
    env->ReleaseStringUTFChars(path, _path);
    return static_cast<jboolean>(exists);
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_verifyWalletPassword(JNIEnv *env, jobject instance,
                                                                   jstring keys_file_name,
                                                                   jstring password,
                                                                   jboolean watch_only) {
    const char *_keys_file_name = env->GetStringUTFChars(keys_file_name, nullptr);
    if (_keys_file_name == nullptr) return JNI_FALSE;
    const char *_password = env->GetStringUTFChars(password, nullptr);
    if (_password == nullptr) {
        env->ReleaseStringUTFChars(keys_file_name, _keys_file_name);
        return JNI_FALSE;
    }
    bool passwordOk =
            Monero::WalletManagerFactory::getWalletManager()->verifyWalletPassword(
                    std::string(_keys_file_name), std::string(_password), watch_only);
    env->ReleaseStringUTFChars(keys_file_name, _keys_file_name);
    env->ReleaseStringUTFChars(password, _password);
    return static_cast<jboolean>(passwordOk);
}

//virtual int queryWalletHardware(const std::string &keys_file_name, const std::string &password) const = 0;
JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_queryWalletDeviceJ(JNIEnv *env, jobject instance,
                                                                 jstring keys_file_name,
                                                                 jstring password) {
    const char *_keys_file_name = env->GetStringUTFChars(keys_file_name, nullptr);
    if (_keys_file_name == nullptr) return -1;
    const char *_password = env->GetStringUTFChars(password, nullptr);
    if (_password == nullptr) {
        env->ReleaseStringUTFChars(keys_file_name, _keys_file_name);
        return -1;
    }
    Monero::Wallet::Device device_type;
    bool ok = Monero::WalletManagerFactory::getWalletManager()->
            queryWalletDevice(device_type, std::string(_keys_file_name), std::string(_password));
    env->ReleaseStringUTFChars(keys_file_name, _keys_file_name);
    env->ReleaseStringUTFChars(password, _password);
    if (ok)
        return static_cast<jint>(device_type);
    else
        return -1;
}

JNIEXPORT jobject JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_findWallets(JNIEnv *env, jobject instance,
                                                          jstring path) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    if (_path == nullptr) return nullptr;
    std::vector<std::string> walletPaths =
            Monero::WalletManagerFactory::getWalletManager()->findWallets(std::string(_path));
    env->ReleaseStringUTFChars(path, _path);
    return cpp2java(env, walletPaths);
}

//TODO virtual bool checkPayment(const std::string &address, const std::string &txid, const std::string &txkey, const std::string &daemon_address, uint64_t &received, uint64_t &height, std::string &error) const = 0;

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_setDaemonAddressJ(JNIEnv *env, jobject instance,
                                                                jstring address) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    if (_address == nullptr) return;
    Monero::WalletManagerFactory::getWalletManager()->setDaemonAddress(std::string(_address));
    env->ReleaseStringUTFChars(address, _address);
}

// returns whether the daemon can be reached, and its version number
JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_getDaemonVersion(JNIEnv *env,
                                                               jobject instance) {
    uint32_t version;
    bool isConnected =
            Monero::WalletManagerFactory::getWalletManager()->connected(&version);
    if (!isConnected) version = 0;
    return version;
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_getBlockchainHeight(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockchainHeight();
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_getBlockchainTargetHeight(JNIEnv *env,
                                                                        jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockchainTargetHeight();
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_getNetworkDifficulty(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->networkDifficulty();
}

JNIEXPORT jdouble JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_getMiningHashRate(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->miningHashRate();
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_getBlockTarget(JNIEnv *env, jobject instance) {
    return Monero::WalletManagerFactory::getWalletManager()->blockTarget();
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_isMining(JNIEnv *env, jobject instance) {
    return static_cast<jboolean>(Monero::WalletManagerFactory::getWalletManager()->isMining());
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_startMining(JNIEnv *env, jobject instance,
                                                          jstring address,
                                                          jboolean background_mining,
                                                          jboolean ignore_battery) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    if (_address == nullptr) return JNI_FALSE;
    bool success =
            Monero::WalletManagerFactory::getWalletManager()->startMining(std::string(_address),
                                                                          background_mining,
                                                                          ignore_battery);
    env->ReleaseStringUTFChars(address, _address);
    return static_cast<jboolean>(success);
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_stopMining(JNIEnv *env, jobject instance) {
    return static_cast<jboolean>(Monero::WalletManagerFactory::getWalletManager()->stopMining());
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_resolveOpenAlias(JNIEnv *env, jobject instance,
                                                               jstring address,
                                                               jboolean dnssec_valid) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    if (_address == nullptr) return nullptr;
    bool _dnssec_valid = (bool) dnssec_valid;
    std::string resolvedAlias =
            Monero::WalletManagerFactory::getWalletManager()->resolveOpenAlias(
                    std::string(_address),
                    _dnssec_valid);
    env->ReleaseStringUTFChars(address, _address);
    return env->NewStringUTF(resolvedAlias.c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_setProxy(JNIEnv *env, jobject instance,
                                                       jstring address) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    if (_address == nullptr) return JNI_FALSE;
    bool rc =
            Monero::WalletManagerFactory::getWalletManager()->setProxy(std::string(_address));
    env->ReleaseStringUTFChars(address, _address);
    return rc;
}


//TODO static std::tuple<bool, std::string, std::string, std::string, std::string> checkUpdates(const std::string &software, const std::string &subdir);

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_closeJ(JNIEnv *env, jobject instance,
                                                     jobject walletInstance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, walletInstance);
    bool closeSuccess = Monero::WalletManagerFactory::getWalletManager()->closeWallet(wallet,
                                                                                      false);
    if (closeSuccess) {
        MyWalletListener *walletListener = getHandle<MyWalletListener>(env, walletInstance,
                                                                       "listenerHandle");
        if (walletListener != nullptr) {
            walletListener->deleteGlobalJavaRef(env);
            delete walletListener;
        }
    }
    LOGD("wallet closed");
    return static_cast<jboolean>(closeSuccess);
}




/**********************************/
/************ Wallet **************/
/**********************************/

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getSeed(JNIEnv *env, jobject instance, jstring seedOffset) {
    const char *_seedOffset = env->GetStringUTFChars(seedOffset, nullptr);
    if (_seedOffset == nullptr) return nullptr;
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    jstring seed = env->NewStringUTF(wallet->seed(std::string(_seedOffset)).c_str());
    env->ReleaseStringUTFChars(seedOffset, _seedOffset);
    return seed;
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getSeedLanguage(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->getSeedLanguage().c_str());
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setSeedLanguage(JNIEnv *env, jobject instance,
                                                       jstring language) {
    const char *_language = env->GetStringUTFChars(language, nullptr);
    if (_language == nullptr) return;
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setSeedLanguage(std::string(_language));
    env->ReleaseStringUTFChars(language, _language);
}

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getStatusJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->status();
}

jobject newWalletStatusInstance(JNIEnv *env, int status, const std::string &errorString) {
    jmethodID init = env->GetMethodID(class_WalletStatus, "<init>",
                                      "(ILjava/lang/String;)V");
    jstring _errorString = env->NewStringUTF(errorString.c_str());
    jobject instance = env->NewObject(class_WalletStatus, init, status, _errorString);
    env->DeleteLocalRef(_errorString);
    return instance;
}


JNIEXPORT jobject JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_statusWithErrorString(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    int status;
    std::string errorString;
    wallet->statusWithErrorString(status, errorString);

    return newWalletStatusInstance(env, status, errorString);
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setPassword(JNIEnv *env, jobject instance,
                                                   jstring password) {
    const char *_password = env->GetStringUTFChars(password, nullptr);
    if (_password == nullptr) return JNI_FALSE;
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    bool success = wallet->setPassword(std::string(_password));
    env->ReleaseStringUTFChars(password, _password);
    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getAddressJ(JNIEnv *env, jobject instance,
                                                   jint accountIndex,
                                                   jint addressIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(
            wallet->address((uint32_t) accountIndex, (uint32_t) addressIndex).c_str());
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getPath(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->path().c_str());
}

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_nettype(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->nettype();
}

//TODO virtual void hardForkInfo(uint8_t &version, uint64_t &earliest_height) const = 0;
//TODO virtual bool useForkRules(uint8_t version, int64_t early_blocks) const = 0;

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getIntegratedAddress(JNIEnv *env, jobject instance,
                                                            jstring payment_id) {
    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    if (_payment_id == nullptr) return nullptr;
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    std::string address = wallet->integratedAddress(_payment_id);
    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return env->NewStringUTF(address.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getSecretViewKey(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->secretViewKey().c_str());
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getSecretSpendKey(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->secretSpendKey().c_str());
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getPublicViewKey(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->publicViewKey().c_str());
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getPublicSpendKey(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->publicSpendKey().c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_store(JNIEnv *env, jobject instance,
                                             jstring path) {
    const char *_path = env->GetStringUTFChars(path, nullptr);
    if (_path == nullptr) return JNI_FALSE;
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    bool success = wallet->store(std::string(_path));
    if (!success) {
        LOGE("store() %s", wallet->errorString().c_str());
    }
    env->ReleaseStringUTFChars(path, _path);
    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getFilename(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return env->NewStringUTF(wallet->filename().c_str());
}

//    virtual std::string keysFilename() const = 0;

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_initJ(JNIEnv *env, jobject instance,
                                             jstring daemon_address,
                                             jlong upper_transaction_size_limit,
                                             jstring daemon_username, jstring daemon_password) {
    const char *_daemon_address = env->GetStringUTFChars(daemon_address, nullptr);
    if (_daemon_address == nullptr) return JNI_FALSE;
    const char *_daemon_username = env->GetStringUTFChars(daemon_username, nullptr);
    if (_daemon_username == nullptr) {
        env->ReleaseStringUTFChars(daemon_address, _daemon_address);
        return JNI_FALSE;
    }
    const char *_daemon_password = env->GetStringUTFChars(daemon_password, nullptr);
    if (_daemon_password == nullptr) {
        env->ReleaseStringUTFChars(daemon_address, _daemon_address);
        env->ReleaseStringUTFChars(daemon_username, _daemon_username);
        return JNI_FALSE;
    }
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    bool status = wallet->init(_daemon_address, (uint64_t) upper_transaction_size_limit,
                               _daemon_username,
                               _daemon_password);
    env->ReleaseStringUTFChars(daemon_address, _daemon_address);
    env->ReleaseStringUTFChars(daemon_username, _daemon_username);
    env->ReleaseStringUTFChars(daemon_password, _daemon_password);
    return static_cast<jboolean>(status);
}

//    virtual bool createWatchOnly(const std::string &path, const std::string &password, const std::string &language) const = 0;

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setRestoreHeight(JNIEnv *env, jobject instance,
                                                        jlong height) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setRefreshFromBlockHeight((uint64_t) height);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getRestoreHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->getRefreshFromBlockHeight();
}

//    virtual void setRecoveringFromSeed(bool recoveringFromSeed) = 0;
//    virtual bool connectToDaemon() = 0;

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getConnectionStatusJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->connected();
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setTrustedDaemon(JNIEnv *env, jobject instance, jboolean isTrusted) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    LOGD("setTrustedDaemon %s", isTrusted ? "true" : "false");
    wallet->setTrustedDaemon(isTrusted);
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_trustedDaemon(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jboolean>(wallet->trustedDaemon());
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setProxy(JNIEnv *env, jobject instance,
                                                jstring address) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    if (_address == nullptr) return JNI_FALSE;
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    bool rc = wallet->setProxy(std::string(_address));
    env->ReleaseStringUTFChars(address, _address);
    return rc;
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getBalance(JNIEnv *env, jobject instance,
                                                  jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->balance((uint32_t) accountIndex);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getBalanceAll(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->balanceAll();
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getUnlockedBalance(JNIEnv *env, jobject instance,
                                                          jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->unlockedBalance((uint32_t) accountIndex);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getUnlockedBalanceAll(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->unlockedBalanceAll();
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_isWatchOnly(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jboolean>(wallet->watchOnly());
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getBlockChainHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->blockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getApproximateBlockChainHeight(JNIEnv *env,
                                                                      jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->approximateBlockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getDaemonBlockChainHeight(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->daemonBlockChainHeight();
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getDaemonBlockChainTargetHeight(JNIEnv *env,
                                                                       jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->daemonBlockChainTargetHeight();
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_isSynchronizedJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jboolean>(wallet->synchronized());
}

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getDeviceTypeJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    Monero::Wallet::Device device_type = wallet->getDeviceType();
    return static_cast<jint>(device_type);
}

//void cn_slow_hash(const void *data, size_t length, char *hash); // from crypto/hash-ops.h
JNIEXPORT jbyteArray JNICALL
Java_io_horizontalsystems_monerokit_util_KeyStoreHelper_slowHash(JNIEnv *env, jclass clazz,
                                                       jbyteArray data, jint brokenVariant) {
    char hash[HASH_SIZE];
    jsize size = env->GetArrayLength(data);
    if ((brokenVariant > 0) && (size < 200 /*sizeof(union hash_state)*/)) {
        return nullptr;
    }

    jbyte *buffer = env->GetByteArrayElements(data, nullptr);
    if (buffer == nullptr) return nullptr;
    switch (brokenVariant) {
        case 1:
            slow_hash_broken(buffer, hash, 1);
            break;
        case 2:
            slow_hash_broken(buffer, hash, 0);
            break;
        default: // not broken
            slow_hash(buffer, (size_t) size, hash);
    }
    env->ReleaseByteArrayElements(data, buffer, JNI_ABORT); // do not update java byte[]
    jbyteArray result = env->NewByteArray(HASH_SIZE);
    env->SetByteArrayRegion(result, 0, HASH_SIZE, (jbyte *) hash);
    return result;
}

static void sha256StateBytes(const SHA256_CTX &ctx, unsigned char out[32]) {
    for (int i = 0; i < 8; i++) {
        out[4 * i] = static_cast<unsigned char>(ctx.h[i] >> 24);
        out[4 * i + 1] = static_cast<unsigned char>(ctx.h[i] >> 16);
        out[4 * i + 2] = static_cast<unsigned char>(ctx.h[i] >> 8);
        out[4 * i + 3] = static_cast<unsigned char>(ctx.h[i]);
    }
}

// PBKDF2-HMAC-SHA256 (RFC 8018). Every round after the first is two SHA-256 compressions from
// the saved inner/outer key states, with no allocation; OpenSSL's SHA-256 core uses the CPU's
// SHA-256 instructions. PKCS5_PBKDF2_HMAC in this OpenSSL (3.0) re-initialises an HMAC context
// every round and measured several times slower for the same output.
static void pbkdf2HmacSha256(const unsigned char *pass, size_t passLen,
                             const unsigned char *salt, size_t saltLen,
                             uint32_t iterations, unsigned char *out, size_t outLen) {
    unsigned char key[64] = {0};
    unsigned char pad[64];
    unsigned char block[64] = {0};
    unsigned char u[32];
    unsigned char t[32];
    unsigned char counter[4];
    SHA256_CTX inner, outer, ctx;

    if (passLen > sizeof(key)) {
        SHA256_Init(&ctx);
        SHA256_Update(&ctx, pass, passLen);
        SHA256_Final(key, &ctx);
    } else if (passLen > 0) {
        memcpy(key, pass, passLen);
    }
    for (size_t i = 0; i < sizeof(pad); i++) pad[i] = key[i] ^ 0x36;
    SHA256_Init(&inner);
    SHA256_Update(&inner, pad, sizeof(pad));
    for (size_t i = 0; i < sizeof(pad); i++) pad[i] = key[i] ^ 0x5c;
    SHA256_Init(&outer);
    SHA256_Update(&outer, pad, sizeof(pad));

    // later rounds hash a 32-byte message after the 64-byte key block: one padded block
    block[32] = 0x80;
    block[62] = 0x03; // message length (64 + 32) * 8 = 768 bits, big endian

    for (uint32_t index = 1; outLen > 0; index++) {
        counter[0] = static_cast<unsigned char>(index >> 24);
        counter[1] = static_cast<unsigned char>(index >> 16);
        counter[2] = static_cast<unsigned char>(index >> 8);
        counter[3] = static_cast<unsigned char>(index);

        // U1 = HMAC(P, S || INT(index))
        ctx = inner;
        if (saltLen > 0) SHA256_Update(&ctx, salt, saltLen);
        SHA256_Update(&ctx, counter, sizeof(counter));
        SHA256_Final(u, &ctx);
        ctx = outer;
        SHA256_Update(&ctx, u, sizeof(u));
        SHA256_Final(u, &ctx);
        memcpy(t, u, sizeof(t));

        // U_j = HMAC(P, U_j-1); T = U1 ^ U2 ^ ... ^ Uc
        for (uint32_t round = 1; round < iterations; round++) {
            memcpy(block, u, sizeof(u));
            ctx = inner;
            SHA256_Transform(&ctx, block);
            sha256StateBytes(ctx, block); // inner digest becomes the outer message
            ctx = outer;
            SHA256_Transform(&ctx, block);
            sha256StateBytes(ctx, u);
            for (size_t k = 0; k < sizeof(t); k++) t[k] ^= u[k];
        }

        const size_t n = outLen < sizeof(t) ? outLen : sizeof(t);
        memcpy(out, t, n);
        out += n;
        outLen -= n;
    }

    OPENSSL_cleanse(key, sizeof(key));
    OPENSSL_cleanse(pad, sizeof(pad));
    OPENSSL_cleanse(block, sizeof(block));
    OPENSSL_cleanse(u, sizeof(u));
    OPENSSL_cleanse(t, sizeof(t));
    OPENSSL_cleanse(&inner, sizeof(inner));
    OPENSSL_cleanse(&outer, sizeof(outer));
    OPENSSL_cleanse(&ctx, sizeof(ctx));
}

JNIEXPORT jbyteArray JNICALL
Java_io_horizontalsystems_monerokit_util_NativeCrypto_pbkdf2HmacSha256J(JNIEnv *env, jclass clazz,
                                                                        jbyteArray password,
                                                                        jbyteArray salt,
                                                                        jint iterations,
                                                                        jint keyLength) {
    if (password == nullptr || salt == nullptr || iterations < 1 || keyLength < 1 ||
        keyLength > 1024) {
        return nullptr;
    }
    const jsize passLen = env->GetArrayLength(password);
    const jsize saltLen = env->GetArrayLength(salt);
    std::vector<unsigned char> pass(static_cast<size_t>(passLen) + 1);
    std::vector<unsigned char> saltBytes(static_cast<size_t>(saltLen) + 1);
    std::vector<unsigned char> key(static_cast<size_t>(keyLength));
    env->GetByteArrayRegion(password, 0, passLen, reinterpret_cast<jbyte *>(pass.data()));
    env->GetByteArrayRegion(salt, 0, saltLen, reinterpret_cast<jbyte *>(saltBytes.data()));

    pbkdf2HmacSha256(pass.data(), static_cast<size_t>(passLen),
                     saltBytes.data(), static_cast<size_t>(saltLen),
                     static_cast<uint32_t>(iterations), key.data(), key.size());
    OPENSSL_cleanse(pass.data(), pass.size());

    jbyteArray result = env->NewByteArray(keyLength);
    if (result != nullptr) {
        env->SetByteArrayRegion(result, 0, keyLength, reinterpret_cast<const jbyte *>(key.data()));
    }
    OPENSSL_cleanse(key.data(), key.size());
    return result;
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getDisplayAmount(JNIEnv *env, jclass clazz,
                                                        jlong amount) {
    return env->NewStringUTF(Monero::Wallet::displayAmount(amount).c_str());
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getAmountFromString(JNIEnv *env, jclass clazz,
                                                           jstring amount) {
    const char *_amount = env->GetStringUTFChars(amount, nullptr);
    if (_amount == nullptr) return 0;
    uint64_t x = Monero::Wallet::amountFromString(_amount);
    env->ReleaseStringUTFChars(amount, _amount);
    return x;
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getAmountFromDouble(JNIEnv *env, jclass clazz,
                                                           jdouble amount) {
    return Monero::Wallet::amountFromDouble(amount);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_generatePaymentId(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(Monero::Wallet::genPaymentId().c_str());
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_isPaymentIdValid(JNIEnv *env, jclass clazz,
                                                        jstring payment_id) {
    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    if (_payment_id == nullptr) return JNI_FALSE;
    bool isValid = Monero::Wallet::paymentIdValid(_payment_id);
    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return static_cast<jboolean>(isValid);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_isKeyValid(JNIEnv *env, jclass clazz, jstring secret_key, jstring address, jboolean is_view_key, jint networkType) {
    const char *_secret_key = env->GetStringUTFChars(secret_key, nullptr);
    if (_secret_key == nullptr) return nullptr;
    const char *_address = env->GetStringUTFChars(address, nullptr);
    if (_address == nullptr) {
        env->ReleaseStringUTFChars(secret_key, _secret_key);
        return nullptr;
    }
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    std::string errorString;
    bool isValid = Monero::Wallet::keyValid(_secret_key, _address, is_view_key, _networkType, errorString);
    env->ReleaseStringUTFChars(secret_key, _secret_key);
    env->ReleaseStringUTFChars(address, _address);
    if (!isValid) {
        LOGE("isKeyValid() %s", errorString.c_str());
        return env->NewStringUTF(errorString.c_str());
    }
    return nullptr;
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_isAddressValid(JNIEnv *env, jclass clazz,
                                                      jstring address, jint networkType) {
    const char *_address = env->GetStringUTFChars(address, nullptr);
    if (_address == nullptr) return JNI_FALSE;
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    bool isValid = Monero::Wallet::addressValid(_address, _networkType);
    env->ReleaseStringUTFChars(address, _address);
    return static_cast<jboolean>(isValid);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getPaymentIdFromAddress(JNIEnv *env, jclass clazz,
                                                               jstring address,
                                                               jint networkType) {
    Monero::NetworkType _networkType = static_cast<Monero::NetworkType>(networkType);
    const char *_address = env->GetStringUTFChars(address, nullptr);
    if (_address == nullptr) return nullptr;
    std::string payment_id = Monero::Wallet::paymentIdFromAddress(_address, _networkType);
    env->ReleaseStringUTFChars(address, _address);
    return env->NewStringUTF(payment_id.c_str());
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getMaximumAllowedAmount(JNIEnv *env, jclass clazz) {
    return Monero::Wallet::maximumAllowedAmount();
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_startRefresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->startRefresh();
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_pauseRefresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->pauseRefresh();
}

// wallet2::stop(): ends the block loop of a refresh pass in progress; the refresh thread keeps running
JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_interruptRefresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->stop();
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setOffline(JNIEnv *env, jobject instance,
                                                           jboolean offline) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setOffline(offline);
}

static bool sameAddress(const sockaddr_storage &peer, const sockaddr_storage &node) {
    if (peer.ss_family == AF_INET && node.ss_family == AF_INET) {
        return reinterpret_cast<const sockaddr_in &>(peer).sin_addr.s_addr ==
               reinterpret_cast<const sockaddr_in &>(node).sin_addr.s_addr;
    }
    if (peer.ss_family == AF_INET6 && node.ss_family == AF_INET6) {
        return memcmp(&reinterpret_cast<const sockaddr_in6 &>(peer).sin6_addr,
                      &reinterpret_cast<const sockaddr_in6 &>(node).sin6_addr,
                      sizeof(in6_addr)) == 0;
    }
    // a dual-stack socket reports an IPv4 peer as ::ffff:a.b.c.d
    if (peer.ss_family == AF_INET6 && node.ss_family == AF_INET) {
        const in6_addr &mapped = reinterpret_cast<const sockaddr_in6 &>(peer).sin6_addr;
        return IN6_IS_ADDR_V4MAPPED(&mapped) &&
               memcmp(&mapped.s6_addr[12],
                      &reinterpret_cast<const sockaddr_in &>(node).sin_addr, 4) == 0;
    }
    return false;
}

static uint16_t peerPort(const sockaddr_storage &peer) {
    if (peer.ss_family == AF_INET) return ntohs(reinterpret_cast<const sockaddr_in &>(peer).sin_port);
    if (peer.ss_family == AF_INET6) return ntohs(reinterpret_cast<const sockaddr_in6 &>(peer).sin6_port);
    return 0;
}

// A TCP socket whose connect() has not completed. It has no peer address to match yet.
static bool isConnecting(int fd) {
    sockaddr_storage local{};
    socklen_t localLength = sizeof(local);
    if (getsockname(fd, reinterpret_cast<sockaddr *>(&local), &localLength) != 0) return false;
    if (local.ss_family != AF_INET && local.ss_family != AF_INET6) return false;
    tcp_info info{};
    socklen_t infoLength = sizeof(info);
    if (getsockopt(fd, IPPROTO_TCP, TCP_INFO, &info, &infoLength) != 0) return false;
    return info.tcpi_state == TCP_SYN_SENT;
}

// Shuts down every TCP connection of this process to host:port and returns how many it cut.
// wallet2 holds its daemon RPC mutex for the whole of a request, up to its 3.5 min rpc_timeout, and has
// no way to cancel one: a request stuck on a dead connection blocks setOffline(), close() and every other
// RPC of that wallet. shutdown() wakes the blocked read with EOF, so the request fails now.
// With connecting set, sockets still waiting for a SYN-ACK are cut as well (a node that drops packets);
// their destination is unknown, so only callers that are tearing a wallet down pass it.
// When the host does not resolve, only a non-web port is matched on its own.
JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_util_NodeSockets_abortJ(JNIEnv *env, jclass clazz,
                                                            jstring host, jint port,
                                                            jboolean connecting) {
    const char *_host = env->GetStringUTFChars(host, nullptr);
    if (_host == nullptr) return -1;
    std::string hostName(_host);
    env->ReleaseStringUTFChars(host, _host);

    std::vector<sockaddr_storage> nodeAddresses;
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo *resolved = nullptr;
    if (getaddrinfo(hostName.c_str(), nullptr, &hints, &resolved) == 0) {
        for (addrinfo *ai = resolved; ai != nullptr; ai = ai->ai_next) {
            if (ai->ai_addrlen > sizeof(sockaddr_storage)) continue;
            sockaddr_storage address{};
            memcpy(&address, ai->ai_addr, ai->ai_addrlen);
            nodeAddresses.push_back(address);
        }
        freeaddrinfo(resolved);
    }
    // unresolved: a Monero RPC port alone is specific enough, a web port is not
    bool matchEstablished = !nodeAddresses.empty() || (port != 80 && port != 443);
    if (!matchEstablished) LOGW("abortJ: %s did not resolve, not matching port %d alone", hostName.c_str(), port);

    DIR *fds = opendir("/proc/self/fd");
    if (fds == nullptr) return -1;
    int cut = 0;
    while (dirent *entry = readdir(fds)) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        int fd = atoi(entry->d_name);
        if (fd == dirfd(fds)) continue;
        struct stat info{};
        if (fstat(fd, &info) != 0 || !S_ISSOCK(info.st_mode)) continue;
        int type = 0;
        socklen_t typeLength = sizeof(type);
        if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &typeLength) != 0 || type != SOCK_STREAM) continue;
        sockaddr_storage peer{};
        socklen_t peerLength = sizeof(peer);
        if (getpeername(fd, reinterpret_cast<sockaddr *>(&peer), &peerLength) != 0) {
            if (connecting && errno == ENOTCONN && isConnecting(fd) && shutdown(fd, SHUT_RDWR) == 0) cut++;
            continue;
        }
        if (!matchEstablished || peerPort(peer) != port) continue;
        bool match = nodeAddresses.empty();
        for (const sockaddr_storage &node : nodeAddresses) {
            if (sameAddress(peer, node)) {
                match = true;
                break;
            }
        }
        if (match && shutdown(fd, SHUT_RDWR) == 0) cut++;
    }
    closedir(fds);
    return cut;
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_refresh(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jboolean>(wallet->refresh());
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_refreshAsync(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->refreshAsync();
}

//TODO virtual bool rescanBlockchain() = 0;

//virtual void rescanBlockchainAsync() = 0;
JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_rescanBlockchainAsyncJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->rescanBlockchainAsync();
}


//TODO virtual void setAutoRefreshInterval(int millis) = 0;
//TODO virtual int autoRefreshInterval() const = 0;

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_createTransactionMultDest(JNIEnv *env, jobject instance,
                                                                 jobjectArray destinations,
                                                                 jstring payment_id,
                                                                 jlongArray amounts,
                                                                 jint mixin_count,
                                                                 jint priority,
                                                                 jint accountIndex,
                                                                 jintArray subaddresses) {
    std::vector<std::string> dst_addr;
    std::vector<uint64_t> amount;

    int destSize = env->GetArrayLength(destinations);
    assert(destSize == env->GetArrayLength(amounts));
    jlong *_amounts = env->GetLongArrayElements(amounts, nullptr);
    if (_amounts == nullptr) return 0;
    for (int i = 0; i < destSize; i++) {
        jstring dest = (jstring) env->GetObjectArrayElement(destinations, i);
        const char *_dest = (dest != nullptr) ? env->GetStringUTFChars(dest, nullptr) : nullptr;
        if (_dest == nullptr) {
            env->ReleaseLongArrayElements(amounts, _amounts, 0);
            return 0;
        }
        dst_addr.emplace_back(_dest);
        env->ReleaseStringUTFChars(dest, _dest);
        amount.emplace_back((uint64_t) _amounts[i]);
    }
    env->ReleaseLongArrayElements(amounts, _amounts, 0);

    std::set<uint32_t> subaddr_indices;
    if (subaddresses != nullptr) {
        int subaddrSize = env->GetArrayLength(subaddresses);
        jint *_subaddresses = env->GetIntArrayElements(subaddresses, nullptr);
        if (_subaddresses == nullptr) return 0;
        for (int i = 0; i < subaddrSize; i++) {
            subaddr_indices.insert((uint32_t) _subaddresses[i]);
        }
        env->ReleaseIntArrayElements(subaddresses, _subaddresses, 0);
    }

    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    if (_payment_id == nullptr) return 0;

    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    Monero::PendingTransaction *tx =
            wallet->createTransactionMultDest(dst_addr, _payment_id,
                                              amount, (uint32_t) mixin_count,
                                              _priority,
                                              (uint32_t) accountIndex,
                                              subaddr_indices);

    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_createTransactionJ(JNIEnv *env, jobject instance,
                                                          jstring dst_addr, jstring payment_id,
                                                          jlong amount, jint mixin_count,
                                                          jint priority,
                                                          jint accountIndex) {

    const char *_dst_addr = env->GetStringUTFChars(dst_addr, nullptr);
    if (_dst_addr == nullptr) return 0;
    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    if (_payment_id == nullptr) {
        env->ReleaseStringUTFChars(dst_addr, _dst_addr);
        return 0;
    }
    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    Monero::PendingTransaction *tx = wallet->createTransaction(_dst_addr, _payment_id,
                                                               amount, (uint32_t) mixin_count,
                                                               _priority,
                                                               (uint32_t) accountIndex);

    env->ReleaseStringUTFChars(dst_addr, _dst_addr);
    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_createSweepTransaction(JNIEnv *env, jobject instance,
                                                              jstring dst_addr, jstring payment_id,
                                                              jint mixin_count,
                                                              jint priority,
                                                              jint accountIndex) {

    const char *_dst_addr = env->GetStringUTFChars(dst_addr, nullptr);
    if (_dst_addr == nullptr) return 0;
    const char *_payment_id = env->GetStringUTFChars(payment_id, nullptr);
    if (_payment_id == nullptr) {
        env->ReleaseStringUTFChars(dst_addr, _dst_addr);
        return 0;
    }
    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    Monero::optional<uint64_t> empty;

    Monero::PendingTransaction *tx = wallet->createTransaction(_dst_addr, _payment_id,
                                                               empty, (uint32_t) mixin_count,
                                                               _priority,
                                                               (uint32_t) accountIndex);

    env->ReleaseStringUTFChars(dst_addr, _dst_addr);
    env->ReleaseStringUTFChars(payment_id, _payment_id);
    return reinterpret_cast<jlong>(tx);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_createSweepUnmixableTransactionJ(JNIEnv *env,
                                                                        jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    Monero::PendingTransaction *tx = wallet->createSweepUnmixableTransaction();
    return reinterpret_cast<jlong>(tx);
}

//virtual UnsignedTransaction * loadUnsignedTx(const std::string &unsigned_filename) = 0;
//virtual bool submitTransaction(const std::string &fileName) = 0;

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_disposeTransaction(JNIEnv *env, jobject instance,
                                                          jobject pendingTransaction) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    Monero::PendingTransaction *_pendingTransaction =
            getHandle<Monero::PendingTransaction>(env, pendingTransaction);
    wallet->disposeTransaction(_pendingTransaction);
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_estimateTransactionFee(JNIEnv *env, jobject instance,
                                                              jobjectArray addresses,
                                                              jlongArray amounts,
                                                              jint priority) {

    std::vector<std::pair<std::string, uint64_t>> destinations;

    int destSize = env->GetArrayLength(addresses);
    assert(destSize == env->GetArrayLength(amounts));
    jlong *_amounts = env->GetLongArrayElements(amounts, nullptr);
    if (_amounts == nullptr) return 0;
    for (int i = 0; i < destSize; i++) {
        std::pair<std::string, uint64_t> pair;
        jstring dest = (jstring) env->GetObjectArrayElement(addresses, i);
        const char *_dest = (dest != nullptr) ? env->GetStringUTFChars(dest, nullptr) : nullptr;
        if (_dest == nullptr) {
            env->ReleaseLongArrayElements(amounts, _amounts, 0);
            return 0;
        }
        pair.first = _dest;
        env->ReleaseStringUTFChars(dest, _dest);
        pair.second = ((uint64_t) _amounts[i]);
        destinations.emplace_back(pair);
    }
    env->ReleaseLongArrayElements(amounts, _amounts, 0);

    Monero::PendingTransaction::Priority _priority =
            static_cast<Monero::PendingTransaction::Priority>(priority);

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    return static_cast<jlong>(wallet->estimateTransactionFee(destinations, _priority));
}

//virtual bool exportKeyImages(const std::string &filename) = 0;
//virtual bool importKeyImages(const std::string &filename) = 0;


//virtual TransactionHistory * history() const = 0;
JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getHistoryJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return reinterpret_cast<jlong>(wallet->history());
}

//virtual AddressBook * addressBook() const = 0;

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getCoinsJ(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return reinterpret_cast<jlong>(wallet->coins());
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setListenerJ(JNIEnv *env, jobject instance,
                                                    jobject javaListener) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setListener(nullptr); // clear old listener
    // delete old listener
    MyWalletListener *oldListener = getHandle<MyWalletListener>(env, instance,
                                                                "listenerHandle");
    if (oldListener != nullptr) {
        oldListener->deleteGlobalJavaRef(env);
        delete oldListener;
    }
    if (javaListener == nullptr) {
        LOGD("null listener");
        return 0;
    } else {
        MyWalletListener *listener = new MyWalletListener(env, javaListener);
        wallet->setListener(listener);
        return reinterpret_cast<jlong>(listener);
    }
}

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getDefaultMixin(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->defaultMixin();
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setDefaultMixin(JNIEnv *env, jobject instance, jint mixin) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return wallet->setDefaultMixin(mixin);
}

JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setUserNote(JNIEnv *env, jobject instance,
                                                   jstring txid, jstring note) {

    const char *_txid = env->GetStringUTFChars(txid, nullptr);
    if (_txid == nullptr) return JNI_FALSE;
    const char *_note = env->GetStringUTFChars(note, nullptr);
    if (_note == nullptr) {
        env->ReleaseStringUTFChars(txid, _txid);
        return JNI_FALSE;
    }

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    bool success = wallet->setUserNote(_txid, _note);

    env->ReleaseStringUTFChars(txid, _txid);
    env->ReleaseStringUTFChars(note, _note);

    return static_cast<jboolean>(success);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getUserNote(JNIEnv *env, jobject instance,
                                                   jstring txid) {

    const char *_txid = env->GetStringUTFChars(txid, nullptr);
    if (_txid == nullptr) return nullptr;

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string note = wallet->getUserNote(_txid);

    env->ReleaseStringUTFChars(txid, _txid);
    return env->NewStringUTF(note.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getTxKey(JNIEnv *env, jobject instance,
                                                jstring txid) {

    const char *_txid = env->GetStringUTFChars(txid, nullptr);
    if (_txid == nullptr) return nullptr;

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string txKey = wallet->getTxKey(_txid);

    env->ReleaseStringUTFChars(txid, _txid);
    return env->NewStringUTF(txKey.c_str());
}

//virtual void addSubaddressAccount(const std::string& label) = 0;
JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_addAccount(JNIEnv *env, jobject instance,
                                                  jstring label) {

    const char *_label = env->GetStringUTFChars(label, nullptr);
    if (_label == nullptr) return;

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->addSubaddressAccount(_label);

    env->ReleaseStringUTFChars(label, _label);
}

//virtual std::string getSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex) const = 0;
JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getSubaddressLabel(JNIEnv *env, jobject instance,
                                                          jint accountIndex, jint addressIndex) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);

    std::string label = wallet->getSubaddressLabel((uint32_t) accountIndex,
                                                   (uint32_t) addressIndex);

    return env->NewStringUTF(label.c_str());
}

//virtual void setSubaddressLabel(uint32_t accountIndex, uint32_t addressIndex, const std::string &label) = 0;
JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_setSubaddressLabel(JNIEnv *env, jobject instance,
                                                          jint accountIndex, jint addressIndex,
                                                          jstring label) {

    const char *_label = env->GetStringUTFChars(label, nullptr);
    if (_label == nullptr) return;

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->setSubaddressLabel(accountIndex, addressIndex, _label);

    env->ReleaseStringUTFChars(label, _label);
}

// virtual size_t numSubaddressAccounts() const = 0;
JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getNumAccounts(JNIEnv *env, jobject instance) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jint>(wallet->numSubaddressAccounts());
}

//virtual size_t numSubaddresses(uint32_t accountIndex) const = 0;
JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getNumSubaddresses(JNIEnv *env, jobject instance,
                                                          jint accountIndex) {
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    return static_cast<jint>(wallet->numSubaddresses(accountIndex));
}

//virtual void addSubaddress(uint32_t accountIndex, const std::string &label) = 0;
JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_addSubaddress(JNIEnv *env, jobject instance,
                                                     jint accountIndex,
                                                     jstring label) {

    const char *_label = env->GetStringUTFChars(label, nullptr);
    if (_label == nullptr) return;
    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    wallet->addSubaddress(accountIndex, _label);
    env->ReleaseStringUTFChars(label, _label);
}

/*JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_Wallet_getLastSubaddress(JNIEnv *env, jobject instance,
                                                         jint accountIndex) {

    Monero::Wallet *wallet = getHandle<Monero::Wallet>(env, instance);
    size_t num = wallet->numSubaddresses(accountIndex);
    //wallet->subaddress()->getAll()[num]->getAddress().c_str()
    Monero::Subaddress *s = wallet->subaddress();
    s->refresh(accountIndex);
    std::vector<Monero::SubaddressRow *> v = s->getAll();
    return env->NewStringUTF(v[num - 1]->getAddress().c_str());
}
*/
//virtual std::string signMessage(const std::string &message) = 0;
//virtual bool verifySignedMessage(const std::string &message, const std::string &addres, const std::string &signature) const = 0;

//virtual bool parse_uri(const std::string &uri, std::string &address, std::string &payment_id, uint64_t &tvAmount, std::string &tx_description, std::string &recipient_name, std::vector<std::string> &unknown_parameters, std::string &error) = 0;
//virtual bool rescanSpent() = 0;


// TransactionHistory
JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_TransactionHistory_getCount(JNIEnv *env, jobject instance) {
    Monero::TransactionHistory *history = getHandle<Monero::TransactionHistory>(env,
                                                                                instance);
    return history->count();
}

jobject newTransferInstance(JNIEnv *env, uint64_t amount, const std::string &address) {
    jmethodID c = env->GetMethodID(class_Transfer, "<init>",
                                   "(JLjava/lang/String;)V");
    jstring _address = env->NewStringUTF(address.c_str());
    jobject transfer = env->NewObject(class_Transfer, c, static_cast<jlong> (amount), _address);
    env->DeleteLocalRef(_address);
    return transfer;
}

jobject newTransferList(JNIEnv *env, Monero::TransactionInfo *info) {
    const std::vector<Monero::TransactionInfo::Transfer> &transfers = info->transfers();
    if (transfers.empty()) { // don't create empty Lists
        return nullptr;
    }
    // make new ArrayList
    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");
    jobject result = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                    static_cast<jint> (transfers.size()));
    // create Transfer objects and stick them in the List
    for (const Monero::TransactionInfo::Transfer &s: transfers) {
        jobject element = newTransferInstance(env, s.amount, s.address);
        env->CallBooleanMethod(result, java_util_ArrayList_add, element);
        env->DeleteLocalRef(element);
    }
    return result;
}

jobject newTransactionInfo(JNIEnv *env, Monero::TransactionInfo *info) {
    jmethodID c = env->GetMethodID(class_TransactionInfo, "<init>",
                                   "(IZZJJJLjava/lang/String;JLjava/lang/String;IIJJLjava/lang/String;Ljava/util/List;)V");
    jobject transfers = newTransferList(env, info);
    jstring _hash = env->NewStringUTF(info->hash().c_str());
    jstring _paymentId = env->NewStringUTF(info->paymentId().c_str());
    jstring _label = env->NewStringUTF(info->label().c_str());
    uint32_t subaddrIndex = 0;
    if (info->direction() == Monero::TransactionInfo::Direction_In)
        subaddrIndex = *(info->subaddrIndex().begin());
    jobject result = env->NewObject(class_TransactionInfo, c,
                                    info->direction(),
                                    info->isPending(),
                                    info->isFailed(),
                                    static_cast<jlong> (info->amount()),
                                    static_cast<jlong> (info->fee()),
                                    static_cast<jlong> (info->blockHeight()),
                                    _hash,
                                    static_cast<jlong> (info->timestamp()),
                                    _paymentId,
                                    static_cast<jint> (info->subaddrAccount()),
                                    static_cast<jint> (subaddrIndex),
                                    static_cast<jlong> (info->confirmations()),
                                    static_cast<jlong> (info->unlockTime()),
                                    _label,
                                    transfers);
    env->DeleteLocalRef(transfers);
    env->DeleteLocalRef(_hash);
    env->DeleteLocalRef(_paymentId);
    return result;
}

#include <stdio.h>
#include <stdlib.h>

// Coins

jobject newCoinsInfo(JNIEnv *env, Monero::CoinsInfo *info) {
    jstring _hash = env->NewStringUTF(info->hash().c_str());

    jmethodID c = env->GetMethodID(class_CoinsInfo, "<init>", "(IIJJLjava/lang/String;ZZJZ)V");
    jobject result = env->NewObject(class_CoinsInfo, c,
                                    static_cast<jint> (info->subaddrAccount()),
                                    static_cast<jint> (info->subaddrIndex()),
                                    static_cast<jlong> (info->amount()),
                                    static_cast<jlong> (info->blockHeight()),
                                    _hash,
                                    info->spent(),
                                    info->frozen(),
                                    static_cast<jlong> (info->unlockTime()),
                                    info->unlocked());
    env->DeleteLocalRef(_hash);
    return result;
}

jobject coinsInfoArrayList(JNIEnv *env, const std::vector<Monero::CoinsInfo *> &vector,
                           uint32_t accountIndex, bool unspentOnly) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject arrayList = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                       static_cast<jint> (vector.size()));
    for (Monero::CoinsInfo *s: vector) {
        if (s->subaddrAccount() != accountIndex) continue;
        if (s->spent() && unspentOnly) continue;
        jobject info = newCoinsInfo(env, s);
        env->CallBooleanMethod(arrayList, java_util_ArrayList_add, info);
        env->DeleteLocalRef(info);
    }
    return arrayList;
}

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_Coins_getCount(JNIEnv *env, jobject instance) {
    Monero::Coins *coins = getHandle<Monero::Coins>(env, instance);
    return coins->count();
}

JNIEXPORT jobject JNICALL
Java_io_horizontalsystems_monerokit_model_Coins_refresh(JNIEnv *env, jobject instance, jint accountIndex,
                                              jboolean unspentOnly) {
    Monero::Coins *coins = getHandle<Monero::Coins>(env, instance);
    coins->refresh();
    return coinsInfoArrayList(env, coins->getAll(), (uint32_t) accountIndex, unspentOnly);
}

jobject
transactionInfoArrayList(JNIEnv *env, const std::vector<Monero::TransactionInfo *> &vector,
                         uint32_t accountIndex) {

    jmethodID java_util_ArrayList_ = env->GetMethodID(class_ArrayList, "<init>", "(I)V");
    jmethodID java_util_ArrayList_add = env->GetMethodID(class_ArrayList, "add",
                                                         "(Ljava/lang/Object;)Z");

    jobject arrayList = env->NewObject(class_ArrayList, java_util_ArrayList_,
                                       static_cast<jint> (vector.size()));
    for (Monero::TransactionInfo *s: vector) {
        if (s->subaddrAccount() != accountIndex) continue;
        jobject info = newTransactionInfo(env, s);
        env->CallBooleanMethod(arrayList, java_util_ArrayList_add, info);
        env->DeleteLocalRef(info);
    }
    return arrayList;
}

JNIEXPORT jobject JNICALL
Java_io_horizontalsystems_monerokit_model_TransactionHistory_refreshJ(JNIEnv *env, jobject instance,
                                                            jint accountIndex) {
    Monero::TransactionHistory *history = getHandle<Monero::TransactionHistory>(env,
                                                                                instance);
    history->refresh();
    return transactionInfoArrayList(env, history->getAll(), (uint32_t) accountIndex);
}

// TransactionInfo is implemented in Java - no need here

JNIEXPORT jint JNICALL
Java_io_horizontalsystems_monerokit_model_PendingTransaction_getStatusJ(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return tx->status();
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_PendingTransaction_getErrorString(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return env->NewStringUTF(tx->errorString().c_str());
}

// commit transaction or save to file if filename is provided.
JNIEXPORT jboolean JNICALL
Java_io_horizontalsystems_monerokit_model_PendingTransaction_commit(JNIEnv *env, jobject instance,
                                                          jstring filename, jboolean overwrite) {

    const char *_filename = env->GetStringUTFChars(filename, nullptr);
    if (_filename == nullptr) return JNI_FALSE;

    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    bool success = tx->commit(_filename, overwrite);

    env->ReleaseStringUTFChars(filename, _filename);
    return static_cast<jboolean>(success);
}


JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_PendingTransaction_getAmount(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return static_cast<jlong>(tx->amount());
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_PendingTransaction_getDust(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return static_cast<jlong>(tx->dust());
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_PendingTransaction_getFee(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return static_cast<jlong>(tx->fee());
}

// TODO this returns a vector of strings - deal with this later - for now return first one
JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_PendingTransaction_getFirstTxIdJ(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    std::vector<std::string> txids = tx->txid();
    if (!txids.empty())
        return env->NewStringUTF(txids.front().c_str());
    else
        return nullptr;
}

JNIEXPORT jlong JNICALL
Java_io_horizontalsystems_monerokit_model_PendingTransaction_getTxCount(JNIEnv *env, jobject instance) {
    Monero::PendingTransaction *tx = getHandle<Monero::PendingTransaction>(env, instance);
    return static_cast<jlong>(tx->txCount());
}


// these are all in Monero::Wallet - which I find wrong, so they are here!
//static void init(const char *argv0, const char *default_log_base_name);
//static void debug(const std::string &category, const std::string &str);
//static void info(const std::string &category, const std::string &str);
//static void warning(const std::string &category, const std::string &str);
//static void error(const std::string &category, const std::string &str);
JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_initLogger(JNIEnv *env, jclass clazz,
                                                         jstring argv0,
                                                         jstring default_log_base_name) {

    const char *_argv0 = env->GetStringUTFChars(argv0, nullptr);
    if (_argv0 == nullptr) return;
    const char *_default_log_base_name = env->GetStringUTFChars(default_log_base_name, nullptr);
    if (_default_log_base_name == nullptr) {
        env->ReleaseStringUTFChars(argv0, _argv0);
        return;
    }

    Monero::Wallet::init(_argv0, _default_log_base_name);

    env->ReleaseStringUTFChars(argv0, _argv0);
    env->ReleaseStringUTFChars(default_log_base_name, _default_log_base_name);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_logDebug(JNIEnv *env, jclass clazz,
                                                       jstring category, jstring message) {

    const char *_category = env->GetStringUTFChars(category, nullptr);
    if (_category == nullptr) return;
    const char *_message = env->GetStringUTFChars(message, nullptr);
    if (_message == nullptr) {
        env->ReleaseStringUTFChars(category, _category);
        return;
    }

    Monero::Wallet::debug(_category, _message);

    env->ReleaseStringUTFChars(category, _category);
    env->ReleaseStringUTFChars(message, _message);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_logInfo(JNIEnv *env, jclass clazz,
                                                      jstring category, jstring message) {

    const char *_category = env->GetStringUTFChars(category, nullptr);
    if (_category == nullptr) return;
    const char *_message = env->GetStringUTFChars(message, nullptr);
    if (_message == nullptr) {
        env->ReleaseStringUTFChars(category, _category);
        return;
    }

    Monero::Wallet::info(_category, _message);

    env->ReleaseStringUTFChars(category, _category);
    env->ReleaseStringUTFChars(message, _message);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_logWarning(JNIEnv *env, jclass clazz,
                                                         jstring category, jstring message) {

    const char *_category = env->GetStringUTFChars(category, nullptr);
    if (_category == nullptr) return;
    const char *_message = env->GetStringUTFChars(message, nullptr);
    if (_message == nullptr) {
        env->ReleaseStringUTFChars(category, _category);
        return;
    }

    Monero::Wallet::warning(_category, _message);

    env->ReleaseStringUTFChars(category, _category);
    env->ReleaseStringUTFChars(message, _message);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_logError(JNIEnv *env, jclass clazz,
                                                       jstring category, jstring message) {

    const char *_category = env->GetStringUTFChars(category, nullptr);
    if (_category == nullptr) return;
    const char *_message = env->GetStringUTFChars(message, nullptr);
    if (_message == nullptr) {
        env->ReleaseStringUTFChars(category, _category);
        return;
    }

    Monero::Wallet::error(_category, _message);

    env->ReleaseStringUTFChars(category, _category);
    env->ReleaseStringUTFChars(message, _message);
}

JNIEXPORT void JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_setLogLevel(JNIEnv *env, jclass clazz,
                                                          jint level) {
    Monero::WalletManagerFactory::setLogLevel(level);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_moneroVersion(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF(MONERO_VERSION);
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_generateKey(JNIEnv *env, jclass clazz, jstring seed, jstring seed_offset, jboolean private_key, jboolean spend_key) {
    const char *_seed = env->GetStringUTFChars(seed, nullptr);
    if (_seed == nullptr) return nullptr;
    const char *_seed_offset = env->GetStringUTFChars(seed_offset, nullptr);
    if (_seed_offset == nullptr) {
        env->ReleaseStringUTFChars(seed, _seed);
        return nullptr;
    }

    std::string key = Monero::Wallet::generateKey(_seed, _seed_offset, private_key, spend_key);

    env->ReleaseStringUTFChars(seed, _seed);
    env->ReleaseStringUTFChars(seed_offset, _seed_offset);

    return env->NewStringUTF(key.c_str());
}

JNIEXPORT jstring JNICALL
Java_io_horizontalsystems_monerokit_model_WalletManager_generateAddress(JNIEnv *env, jclass clazz, jstring seed, jstring seed_offset, jint account_index, jint address_index,  jboolean testnet) {
    const char *_seed = env->GetStringUTFChars(seed, nullptr);
    if (_seed == nullptr) return nullptr;
    const char *_seed_offset = env->GetStringUTFChars(seed_offset, nullptr);
    if (_seed_offset == nullptr) {
        env->ReleaseStringUTFChars(seed, _seed);
        return nullptr;
    }

    std::string key = Monero::Wallet::generateAddress(_seed, _seed_offset, account_index, address_index, testnet);

    env->ReleaseStringUTFChars(seed, _seed);
    env->ReleaseStringUTFChars(seed_offset, _seed_offset);

    return env->NewStringUTF(key.c_str());
}

//
// Ledger Stuff
//

/**
 * @brief LedgerExchange - exchange data with Ledger Device
 * @param command        - buffer for data to send
 * @param cmd_len        - length of send to send
 * @param response       - buffer for received data
 * @param max_resp_len   - size of receive buffer
 *
 * @return length of received data in response or -1 if error
 */
int LedgerExchange(
        unsigned char *command,
        unsigned int cmd_len,
        unsigned char *response,
        unsigned int max_resp_len) {
    LOGD("LedgerExchange");
    JNIEnv *jenv;
    int envStat = attachJVM(&jenv);
    if (envStat == JNI_ERR) return -1;

    jmethodID exchangeMethod = jenv->GetStaticMethodID(class_Ledger, "Exchange", "([B)[B");

    jsize sendLen = static_cast<jsize>(cmd_len);
    jbyteArray dataSend = jenv->NewByteArray(sendLen);
    jenv->SetByteArrayRegion(dataSend, 0, sendLen, (jbyte *) command);
    jbyteArray dataRecv = (jbyteArray) jenv->CallStaticObjectMethod(class_Ledger, exchangeMethod,
                                                                    dataSend);
    jenv->DeleteLocalRef(dataSend);
    if (dataRecv == nullptr) {
        detachJVM(jenv, envStat);
        LOGD("LedgerExchange SCARD_E_NO_READERS_AVAILABLE");
        return -1;
    }
    jsize len = jenv->GetArrayLength(dataRecv);
    LOGD("LedgerExchange SCARD_S_SUCCESS %u/%d", cmd_len, len);
    if (len <= max_resp_len) {
        jenv->GetByteArrayRegion(dataRecv, 0, len, (jbyte *) response);
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        return static_cast<int>(len);;
    } else {
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        LOGE("LedgerExchange SCARD_E_INSUFFICIENT_BUFFER");
        return -1;
    }
}

///**
// * @brief LedgerFind - find Ledger Device and return it's name
// * @param buffer - buffer for name of found device
// * @param len    - length of buffer
// * @return  0 - success
// *         -1 - no device connected / found
// *         -2 - JVM not found
// */
//int LedgerFind(char *buffer, size_t len) {
//    LOGD("LedgerName");
//    JNIEnv *jenv;
//    int envStat = attachJVM(&jenv);
//    if (envStat == JNI_ERR) return -2;
//
//    jmethodID nameMethod = jenv->GetStaticMethodID(class_Ledger, "Name", "()Ljava/lang/String;");
//    jstring name = (jstring) jenv->CallStaticObjectMethod(class_Ledger, nameMethod);
//
//    int ret;
//    if (name != nullptr) {
//        const char *_name = jenv->GetStringUTFChars(name, nullptr);
//        strncpy(buffer, _name, len);
//        jenv->ReleaseStringUTFChars(name, _name);
//        buffer[len - 1] = 0; // terminate in case _name is bigger
//        ret = 0;
//        LOGD("LedgerName is %s", buffer);
//    } else {
//        buffer[0] = 0;
//        ret = -1;
//    }
//
//    detachJVM(jenv, envStat);
//    return ret;
//}

//
// SidekickWallet Stuff
//

/**
 * @brief BtExchange     - exchange data with Monerujo Device
 * @param request        - buffer for data to send
 * @param request_len    - length of data to send
 * @param response       - buffer for received data
 * @param max_resp_len   - size of receive buffer
 *
 * @return length of received data in response or -1 if error, -2 if response buffer too small
 */
int BtExchange(
        unsigned char *request,
        unsigned int request_len,
        unsigned char *response,
        unsigned int max_resp_len) {
    JNIEnv *jenv;
    int envStat = attachJVM(&jenv);
    if (envStat == JNI_ERR) return -16;

    jmethodID exchangeMethod = jenv->GetStaticMethodID(class_BluetoothService, "Exchange",
                                                       "([B)[B");

    auto reqLen = static_cast<jsize>(request_len);
    jbyteArray reqData = jenv->NewByteArray(reqLen);
    jenv->SetByteArrayRegion(reqData, 0, reqLen, (jbyte *) request);
    LOGD("BtExchange cmd: 0x%02x with %u bytes", request[0], reqLen);
    auto dataRecv = (jbyteArray)
            jenv->CallStaticObjectMethod(class_BluetoothService, exchangeMethod, reqData);
    jenv->DeleteLocalRef(reqData);
    if (dataRecv == nullptr) {
        detachJVM(jenv, envStat);
        LOGD("BtExchange: error reading");
        return -1;
    }
    jsize respLen = jenv->GetArrayLength(dataRecv);
    LOGD("BtExchange response is %u bytes", respLen);
    if (respLen <= max_resp_len) {
        jenv->GetByteArrayRegion(dataRecv, 0, respLen, (jbyte *) response);
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        return static_cast<int>(respLen);;
    } else {
        jenv->DeleteLocalRef(dataRecv);
        detachJVM(jenv, envStat);
        LOGE("BtExchange response buffer too small: %u < %u", respLen, max_resp_len);
        return -2;
    }
}

///**
// * @brief ConfirmTransfers
// * @param transfers - string of "fee (':' address ':' amount)+"
// *
// * @return true on accept, false on reject
// */
//bool ConfirmTransfers(const char *transfers) {
//    JNIEnv *jenv;
//    int envStat = attachJVM(&jenv);
//    if (envStat == JNI_ERR) return -16;
//
//    jmethodID confirmMethod = jenv->GetStaticMethodID(class_SidekickService, "ConfirmTransfers",
//                                                      "(Ljava/lang/String;)Z");
//
//    jstring _transfers = jenv->NewStringUTF(transfers);
//    auto confirmed =
//            jenv->CallStaticBooleanMethod(class_SidekickService, confirmMethod, _transfers);
//    jenv->DeleteLocalRef(_transfers);
//    return confirmed;
//}

#ifdef __cplusplus
}
#endif
