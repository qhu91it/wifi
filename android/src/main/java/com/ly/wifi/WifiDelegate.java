package com.ly.wifi;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Collections;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class
WifiDelegate implements PluginRegistry.RequestPermissionsResultListener {
    private Activity activity;
    private WifiManager wifiManager;
    private PermissionManager permissionManager;
    private static final int REQUEST_ACCESS_FINE_LOCATION_PERMISSION = 1;
    private static final int REQUEST_CHANGE_WIFI_STATE_PERMISSION = 2;
    private static final int REQUEST_CHANGE_INTERNET_PERMISSION = 3;
    NetworkChangeReceiver networkReceiver;
    ConnectivityManager connectivityManager;
    ConnectivityManager.NetworkCallback networkCallback;
    /*
    *  Max priority of network to be associated.
    */
    private static final int MAX_PRIORITY = 999999;

    interface PermissionManager {
        boolean isPermissionGranted(String permissionName);

        void askForPermission(String permissionName, int requestCode);
    }

    public WifiDelegate(final Activity activity, final WifiManager wifiManager) {
        this(activity, wifiManager, null, null, new PermissionManager() {

            @Override
            public boolean isPermissionGranted(String permissionName) {
                return ActivityCompat.checkSelfPermission(activity, permissionName) == PackageManager.PERMISSION_GRANTED;
            }

            @Override
            public void askForPermission(String permissionName, int requestCode) {
                ActivityCompat.requestPermissions(activity, new String[]{permissionName}, requestCode);
            }
        });
    }

    private MethodChannel.Result result;
    private MethodCall methodCall;

    WifiDelegate(
            Activity activity,
            WifiManager wifiManager,
            MethodChannel.Result result,
            MethodCall methodCall,
            PermissionManager permissionManager) {
        this.networkReceiver = new NetworkChangeReceiver();
        this.activity = activity;
        this.wifiManager = wifiManager;
        this.result = result;
        this.methodCall = methodCall;
        this.permissionManager = permissionManager;
        this.connectivityManager = (ConnectivityManager)activity.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void getSSID(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        launchSSID();
    }

    public void getLevel(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        launchLevel();
    }

    private void launchSSID() {
        String wifiName = wifiManager != null ? wifiManager.getConnectionInfo().getSSID().replace("\"", "") : "";
        if (!wifiName.isEmpty()) {
            result.success(wifiName);
            clearMethodCallAndResult();
        } else {
            finishWithError("unavailable", "wifi name not available.");
        }
    }

    private void launchLevel() {
        int level = wifiManager != null ? wifiManager.getConnectionInfo().getRssi() : 0;
        if (level != 0) {
            if (level <= 0 && level >= -55) {
                result.success(3);
            } else if (level < -55 && level >= -80) {
                result.success(2);
            } else if (level < -80 && level >= -100) {
                result.success(1);
            } else {
                result.success(0);
            }
            clearMethodCallAndResult();
        } else {
            finishWithError("unavailable", "wifi level not available.");
        }
    }

    public void getIP(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        launchIP();
    }

    private void launchIP() {
        NetworkInfo info = ((ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (info != null && info.isConnected()) {
            if (info.getType() == ConnectivityManager.TYPE_MOBILE) {
                try {
                    for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                        NetworkInterface intf = en.nextElement();
                        for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                            InetAddress inetAddress = enumIpAddr.nextElement();
                            if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                                result.success(inetAddress.getHostAddress());
                                clearMethodCallAndResult();
                            }
                        }
                    }
                } catch (SocketException e) {
                    e.printStackTrace();
                }
            } else if (info.getType() == ConnectivityManager.TYPE_WIFI) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                String ipAddress = intIP2StringIP(wifiInfo.getIpAddress());
                result.success(ipAddress);
                clearMethodCallAndResult();
            }
        } else {
            finishWithError("unavailable", "ip not available.");
        }
    }

    private static String intIP2StringIP(int ip) {
        return (ip & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                (ip >> 24 & 0xFF);
    }

    public void getWifiList(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        if (!permissionManager.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            permissionManager.askForPermission(Manifest.permission.ACCESS_FINE_LOCATION, REQUEST_ACCESS_FINE_LOCATION_PERMISSION);
            return;
        }
        launchWifiList();
    }

    private void launchWifiList() {
        String key = methodCall.argument("key");
        List<HashMap> list = new ArrayList<>();
        if (wifiManager != null) {
            List<ScanResult> scanResultList = wifiManager.getScanResults();
            for (ScanResult scanResult : scanResultList) {
                int level;
                if (scanResult.level <= 0 && scanResult.level >= -55) {
                    level = 3;
                } else if (scanResult.level < -55 && scanResult.level >= -80) {
                    level = 2;
                } else if (scanResult.level < -80 && scanResult.level >= -100) {
                    level = 1;
                } else {
                    level = 0;
                }
                HashMap<String, Object> maps = new HashMap<>();
                if (key.isEmpty()) {
                    maps.put("ssid", scanResult.SSID);
                    maps.put("level", level);
                    list.add(maps);
                } else {
                    if (scanResult.SSID.contains(key)) {
                        maps.put("ssid", scanResult.SSID);
                        maps.put("level", level);
                        list.add(maps);
                    }
                }
            }
        }
        result.success(list);
        clearMethodCallAndResult();
    }

    public void connection(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        if (!permissionManager.isPermissionGranted(Manifest.permission.CHANGE_WIFI_STATE)) {
            permissionManager.askForPermission(Manifest.permission.CHANGE_WIFI_STATE, REQUEST_ACCESS_FINE_LOCATION_PERMISSION);
            return;
        }
        if (!permissionManager.isPermissionGranted(Manifest.permission.INTERNET)) {
            permissionManager.askForPermission(Manifest.permission.INTERNET, REQUEST_CHANGE_INTERNET_PERMISSION);
            return;
        }
        
        connection();
    }

    public void disconnect(MethodCall methodCall, MethodChannel.Result result) {
        if (!setPendingMethodCallAndResult(methodCall, result)) {
            finishWithAlreadyActiveError();
            return;
        }
        disconnect();
    }

    private void connection() {
        String ssid = methodCall.argument("ssid");
        String password = methodCall.argument("password");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiNetworkSpecifier wifiNetworkSpecifier = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build();

            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(wifiNetworkSpecifier)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    super.onAvailable(network);
                    Log.d("NetworkCallback", "onAvailable");
                    connectivityManager.bindProcessToNetwork(network);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            result.success(1);
                            clearMethodCallAndResult();
                        }
                    });
                }

                @Override
                public void onUnavailable() {
                    super.onUnavailable();
                    Log.d("NetworkCallback", "onUnavailable");
                    networkConnectUnable();
                }

                @Override
                public void onLost(@NonNull Network network) {
                    super.onLost(network);
                    Log.d("NetworkCallback", "onLost");
                    networkConnectUnable();
                }
            };
            connectivityManager.requestNetwork(networkRequest, networkCallback);
            return;
        }
        boolean isReconnect = enableNetwork(ssid);
        if (isReconnect) {
            result.success(1);
            clearMethodCallAndResult();
            return;
        }
        WifiConfiguration wifiConfig = createWifiConfig(ssid, password);
        if (wifiConfig == null) {
            finishWithError("unavailable", "wifi config is null!");
            return;
        }
        int netId = wifiManager.addNetwork(wifiConfig);
        if (netId == -1) {
            result.success(0);
            clearMethodCallAndResult();
        } else {
            // support Android O
            // https://stackoverflow.com/questions/50462987/android-o-wifimanager-enablenetwork-cannot-work
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                wifiManager.saveConfiguration();
                wifiManager.disconnect();
                boolean isEnable = wifiManager.enableNetwork(netId, true);
                wifiManager.reconnect();
                result.success(1);
                // >> HELBER
                // ConnectivityManager connection_manager =
                // (ConnectivityManager) activity.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE);
                // NetworkRequest.Builder request = new NetworkRequest.Builder();
                // request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
                // connection_manager.registerNetworkCallback(request.build(), new NetworkCallback() {
                //     @Override
                //     public void onAvailable(Network network) {
                //         ConnectivityManager.setProcessDefaultNetwork(network);
                //     }
                // });

                // final ConnectivityManager manager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
                // NetworkRequest.Builder builder;
                // builder = new NetworkRequest.Builder();
                // builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
                // if (manager != null) {
                //     manager.requestNetwork(builder.build(), new ConnectivityManager.NetworkCallback() {
                //         @Override
                //         public void onAvailable(Network network) {
                //             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //                 // manager.bindProcessToNetwork(network);
                //                 boolean result = ConnectivityManager.setProcessDefaultNetwork(network);
                //                 Log.d("HELBER", "RESULT: "+ result);
                //                 manager.unregisterNetworkCallback(this);
                //             } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                //                 ConnectivityManager.setProcessDefaultNetwork(network);
                //                 manager.unregisterNetworkCallback(this);
                //             }
                //         }
                //     });
                // }
                // << HELBER
                // >> Pileggi
                Network etherNetwork = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ConnectivityManager connectivityManager = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
                    for (Network network : connectivityManager.getAllNetworks()) {
                        NetworkInfo networkInfo = connectivityManager.getNetworkInfo(network);
                        if (networkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                            etherNetwork = network;
                        }
                    }
                }
                // Android 6
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network boundNetwork = connectivityManager.getBoundNetworkForProcess();
                    if (boundNetwork != null) {
                        NetworkInfo boundNetworkInfo = connectivityManager.getNetworkInfo(boundNetwork);
                        if (boundNetworkInfo.getType() != ConnectivityManager.TYPE_ETHERNET) {
                            if (etherNetwork != null) {
                                connectivityManager.bindProcessToNetwork(etherNetwork);
                            }
                        }
                    }
                }
                // << Pileggi
                clearMethodCallAndResult();
            } else {
                networkReceiver.connect(netId);
            }
        }
    }

    private void disconnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectivityManager.bindProcessToNetwork(null);
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
            result.success(1);
            clearMethodCallAndResult();
            return;
        }

        String ssid = methodCall.argument("ssid");
        WifiConfiguration tempConfig = isExist(wifiManager, ssid);
        WifiConfiguration wifiConfig = null;
        if (tempConfig == null) {
            wifiConfig = createWifiConfig(ssid, "");
        }
        if (tempConfig == null && wifiConfig == null) {
            result.success(1);
            clearMethodCallAndResult();
            return;
        }
        wifiManager.disconnect();
        int netId = tempConfig != null ? tempConfig.networkId : wifiManager.addNetwork(wifiConfig);
        wifiManager.disableNetwork(netId);
        //wifiManager.removeNetwork(netId);
        result.success(1);
        clearMethodCallAndResult();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void networkConnectUnable() {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (result != null) {
                    result.success(0);
                    clearMethodCallAndResult();
                }
            }
        });
        connectivityManager.unregisterNetworkCallback(networkCallback);
        networkCallback = null;
    }

    private WifiConfiguration createWifiConfig(String ssid, String Password) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = convertToQuotedString(ssid);
        config.allowedAuthAlgorithms.clear();
        config.allowedGroupCiphers.clear();
        config.allowedKeyManagement.clear();
        config.allowedPairwiseCiphers.clear();
        config.allowedProtocols.clear();

        WifiConfiguration tempConfig = isExist(wifiManager, ssid);
        if (tempConfig != null) {
            wifiManager.removeNetwork(tempConfig.networkId);
        }

        int newPri = getMaxPriority() + 1;
        if(newPri >= MAX_PRIORITY) {
            // We have reached a rare situation.
            newPri = shiftPriorityAndSave();
        }
        config.priority = newPri;

        config.preSharedKey = convertToQuotedString(Password);
        config.hiddenSSID = true;
        config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        config.status = WifiConfiguration.Status.ENABLED;
        return config;
    }

    private WifiConfiguration isExist(WifiManager wifiManager, String ssid) {
        List<WifiConfiguration> existingConfigs = wifiManager.getConfiguredNetworks();
        if(existingConfigs != null) {
            for (WifiConfiguration existingConfig : existingConfigs) {
                if (existingConfig.SSID.equals(convertToQuotedString(ssid))) {
                    return existingConfig;
                }
            }
        }
        return null;
    }

    private boolean setPendingMethodCallAndResult(MethodCall methodCall, MethodChannel.Result result) {
        if (this.result != null) {
            return false;
        }
        this.methodCall = methodCall;
        this.result = result;
        return true;
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean permissionGranted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        switch (requestCode) {
            case REQUEST_ACCESS_FINE_LOCATION_PERMISSION:
                if (permissionGranted) {
                    launchWifiList();
                }
                break;
            case REQUEST_CHANGE_WIFI_STATE_PERMISSION:
                if (permissionGranted) {
                    connection();
                }
                break;
            case REQUEST_CHANGE_INTERNET_PERMISSION:
                if (permissionGranted) {
                    connection();
                }
                break;
            default:
                return false;
        }
        if (!permissionGranted) {
            clearMethodCallAndResult();
        }
        return true;
    }

    private void finishWithAlreadyActiveError() {
        finishWithError("already_active", "wifi is already active");
    }

    private void finishWithError(String errorCode, String errorMessage) {
        result.error(errorCode, errorMessage, null);
        clearMethodCallAndResult();
    }

    private void clearMethodCallAndResult() {
        methodCall = null;
        result = null;
    }

    // support Android O
    // https://stackoverflow.com/questions/50462987/android-o-wifimanager-enablenetwork-cannot-work
    public class NetworkChangeReceiver extends BroadcastReceiver {
        private int netId;
        private boolean willLink = false;

        @Override
        public void onReceive(Context context, Intent intent) {
            NetworkInfo info = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            if (info.getState() == NetworkInfo.State.DISCONNECTED && willLink) {
                wifiManager.enableNetwork(netId, true);
                wifiManager.reconnect();
                result.success(1);
                willLink = false;
                clearMethodCallAndResult();
            }
        }

        public void connect(int netId) {
            this.netId = netId;
            willLink = true;
            wifiManager.disconnect();
        }
    }

    /**
    * Allow a previously configured network to be associated with.
    */
    public boolean enableNetwork(String ssid) {
        boolean state = false;
        List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();

        if(list != null && list.size() > 0) {
            for( WifiConfiguration i : list ) {
                if(i.SSID != null && i.SSID.equals(convertToQuotedString(ssid))) {
                    wifiManager.disconnect();

                    int newPri = getMaxPriority() + 1;
                    if(newPri >= MAX_PRIORITY) {
                        // We have reached a rare situation.
                        newPri = shiftPriorityAndSave();
                    }

                    i.priority = newPri;
                    wifiManager.updateNetwork(i);
                    wifiManager.saveConfiguration();

                    state = wifiManager.enableNetwork(i.networkId, true);
                    wifiManager.reconnect();
                    break;
                }
            }
        }

        return state;
    }

    private int getMaxPriority() {
        final List<WifiConfiguration> configurations = wifiManager.getConfiguredNetworks();
        int pri = 0;
        for (final WifiConfiguration config : configurations) {
            if (config.priority > pri) {
                pri = config.priority;
            }
        }
        return pri;
    }

    private void sortByPriority(final List<WifiConfiguration> configurations) {
        Collections.sort(configurations,
            new Comparator<WifiConfiguration>() {
                @Override
                public int compare(WifiConfiguration object1, WifiConfiguration object2) {
                    return object1.priority - object2.priority;
                }
            });
    }

    private int shiftPriorityAndSave() {
        final List<WifiConfiguration> configurations = wifiManager.getConfiguredNetworks();
        sortByPriority(configurations);
        final int size = configurations.size();
        for (int i = 0; i < size; i++) {
            final WifiConfiguration config = configurations.get(i);
            config.priority = i;
            wifiManager.updateNetwork(config);
        }
        wifiManager.saveConfiguration();
        return size;
    }

    /**
    * Add quotes to string if not already present.
    *
    * @param string
    * @return
    */
    public static String convertToQuotedString(String string) {
        if (TextUtils.isEmpty(string)) {
            return "";
        }

        final int lastPos = string.length() - 1;
        if (lastPos > 0
                && (string.charAt(0) == '"' && string.charAt(lastPos) == '"')) {
            return string;
        }

        return "\"" + string + "\"";
    }
}
