/*
 * Copyright (c) 2017 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.horizontalsystems.monerokit.model;

import io.horizontalsystems.monerokit.data.Node;
import io.horizontalsystems.monerokit.util.RestoreHeight;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import lombok.Getter;
import timber.log.Timber;

public class WalletManager {

    static {
        System.loadLibrary("monerujo");
    }

    private static WalletManager Instance = null;

    public static synchronized WalletManager getInstance() {
        if (WalletManager.Instance == null) {
            WalletManager.Instance = new WalletManager();
        }

        return WalletManager.Instance;
    }

    public String addressPrefix() {
        return addressPrefix(getNetworkType());
    }

    static public String addressPrefix(NetworkType networkType) {
        switch (networkType) {
            case NetworkType_Testnet:
                return "9A-";
            case NetworkType_Mainnet:
                return "4-";
            case NetworkType_Stagenet:
                return "5-";
            default:
                throw new IllegalStateException("Unsupported Network: " + networkType);
        }
    }

    public Wallet openWallet(String path, String password) {
        long walletHandle = openWalletJ(path, password, getNetworkType().getValue());
        Wallet wallet = new Wallet(walletHandle);
        return wallet;
    }

    private native long openWalletJ(String path, String password, int networkType);

    public Wallet recoveryWallet(File aFile, String password,
                                 String mnemonic, String offset,
                                 long restoreHeight) {
        long walletHandle = recoveryWalletJ(aFile.getAbsolutePath(), password,
                mnemonic, offset,
                getNetworkType().getValue(), restoreHeight);
        Wallet wallet = new Wallet(walletHandle);
        return wallet;
    }

    private native long recoveryWalletJ(String path, String password,
                                        String mnemonic, String offset,
                                        int networkType, long restoreHeight);

    public Wallet createWalletWithKeys(File aFile, String password, String language, long restoreHeight,
                                       String addressString, String viewKeyString, String spendKeyString) {
        long walletHandle = createWalletFromKeysJ(aFile.getAbsolutePath(), password,
                language, getNetworkType().getValue(), restoreHeight,
                addressString, viewKeyString, spendKeyString);
        Wallet wallet = new Wallet(walletHandle);
        return wallet;
    }

    private native long createWalletFromKeysJ(String path, String password,
                                              String language,
                                              int networkType,
                                              long restoreHeight,
                                              String addressString,
                                              String viewKeyString,
                                              String spendKeyString);

    public native boolean closeJ(Wallet wallet);

    public boolean close(Wallet wallet) {
        return closeJ(wallet);
    }

    public boolean walletExists(File aFile) {
        return walletExists(aFile.getAbsolutePath());
    }

    public native boolean walletExists(String path);

    public native boolean verifyWalletPassword(String keys_file_name, String password, boolean watch_only);

    public boolean verifyWalletPasswordOnly(String keys_file_name, String password) {
        return queryWalletDeviceJ(keys_file_name, password) >= 0;
    }

    public Wallet.Device queryWalletDevice(String keys_file_name, String password) {
        int device = queryWalletDeviceJ(keys_file_name, password);
        return Wallet.Device.values()[device + 1]; // mapping is monero+1=android
    }

    private native int queryWalletDeviceJ(String keys_file_name, String password);

    //public native List<String> findWallets(String path); // this does not work - some error in boost

    public class WalletInfo implements Comparable<WalletInfo> {
        @Getter
        final private File path;
        @Getter
        final private String name;

        public WalletInfo(File wallet) {
            path = wallet.getParentFile();
            name = wallet.getName();
        }

        @Override
        public int compareTo(WalletInfo another) {
            return name.toLowerCase().compareTo(another.name.toLowerCase());
        }
    }

    public List<WalletInfo> findWallets(File path) {
        List<WalletInfo> wallets = new ArrayList<>();
        Timber.d("Scanning: %s", path.getAbsolutePath());
        File[] found = path.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String filename) {
                return filename.endsWith(".keys");
            }
        });
        for (int i = 0; i < found.length; i++) {
            String filename = found[i].getName();
            File f = new File(found[i].getParent(), filename.substring(0, filename.length() - 5)); // 5 is length of ".keys"+1
            wallets.add(new WalletInfo(f));
        }
        return wallets;
    }

//TODO virtual bool checkPayment(const std::string &address, const std::string &txid, const std::string &txkey, const std::string &daemon_address, uint64_t &received, uint64_t &height, std::string &error) const = 0;

    private String daemonAddress = null;
    private final NetworkType networkType = NetworkType.NetworkType_Mainnet;

    public NetworkType getNetworkType() {
        return networkType;
    }

    // this should not be called on the main thread as it connects to the node (and takes a long time)
    public void setDaemon(Node node) {
        if (node != null) {
            this.daemonAddress = node.getAddress();
            if (networkType != node.getNetworkType())
                throw new IllegalArgumentException("network type does not match");
            this.daemonUsername = node.getUsername();
            this.daemonPassword = node.getPassword();
            setDaemonAddressJ(daemonAddress);
        } else {
            this.daemonAddress = null;
            this.daemonUsername = "";
            this.daemonPassword = "";
            //setDaemonAddressJ(""); // don't disconnect as monero code blocks for many seconds!
            //TODO: need to do something about that later
        }
    }

    public String getDaemonAddress() {
        if (daemonAddress == null) {
            throw new IllegalStateException("use setDaemon() to initialise daemon and net first!");
        }
        return this.daemonAddress;
    }

    private native void setDaemonAddressJ(String address);

    private String daemonUsername = "";

    public String getDaemonUsername() {
        return daemonUsername;
    }

    private String daemonPassword = "";

    public String getDaemonPassword() {
        return daemonPassword;
    }

    public native int getDaemonVersion();

    public native long getBlockchainHeight();

    public native long getBlockchainTargetHeight();

    public native long getNetworkDifficulty();

    public native double getMiningHashRate();

    public native long getBlockTarget();

    public native boolean isMining();

    public native boolean startMining(String address, boolean background_mining, boolean ignore_battery);

    public native boolean stopMining();

    /**
     * Resolves an OpenAlias name with the wallet2 resolver. Writes the DNSSEC
     * flag into dnssecValid[0] when the array has room. Blocks with no time limit.
     */
    public native String resolveOpenAlias(String address, boolean[] dnssecValid);

    public native boolean setProxy(String address);

//TODO static std::tuple<bool, std::string, std::string, std::string, std::string> checkUpdates(const std::string &software, const std::string &subdir);

    static public native void initLogger(String argv0, String defaultLogBaseName);

    //TODO: maybe put these in an enum like in monero core - but why?
    static public int LOGLEVEL_SILENT = -1;
    static public int LOGLEVEL_WARN = 0;
    static public int LOGLEVEL_INFO = 1;
    static public int LOGLEVEL_DEBUG = 2;
    static public int LOGLEVEL_TRACE = 3;
    static public int LOGLEVEL_MAX = 4;

    static public native void setLogLevel(int level);

    static public native void logDebug(String category, String message);

    static public native void logInfo(String category, String message);

    static public native void logWarning(String category, String message);

    static public native void logError(String category, String message);

    static public native String moneroVersion();

    static private native String generateKey(String seed, String seed_offset, boolean private_key, boolean spend_key);

    static private native String generateAddress(String seed, String seed_offset, int account_index, int address_index, boolean testnet);

    static public String getPrivateSpendKey(String mnemonic, String passphrase) {
        return generateKey(mnemonic, passphrase, true, true);
    }

    static public String getAddress(String mnemonic, String passphrase, int accountIndex, int addressIndex) {
        return generateAddress(mnemonic, passphrase, accountIndex, addressIndex, false);
    }

    static public String getPublicSpendKey(String mnemonic, String passphrase) {
        return generateKey(mnemonic, passphrase, false, true);
    }

    static public String getPrivateViewKey(String mnemonic, String passphrase) {
        return generateKey(mnemonic, passphrase, true, false);
    }

    static public String getPublicViewKey(String mnemonic, String passphrase) {
        return generateKey(mnemonic, passphrase, false, false);
    }
}