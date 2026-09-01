package org.fhwifi;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST = 100;
    private WifiManager wifiManager;
    private RecyclerView recyclerView;
    private NetworkAdapter adapter;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button scanBtn, autoBtn;
    private List<NetworkItem> networks = new ArrayList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // ========== خوارزمية fh_ ==========
    private static final Map<Character, Character> HEX_SWAP = new HashMap<>();
    static {
        HEX_SWAP.put('f','0'); HEX_SWAP.put('0','f');
        HEX_SWAP.put('e','1'); HEX_SWAP.put('1','e');
        HEX_SWAP.put('d','2'); HEX_SWAP.put('2','d');
        HEX_SWAP.put('c','3'); HEX_SWAP.put('3','c');
        HEX_SWAP.put('b','4'); HEX_SWAP.put('4','b');
        HEX_SWAP.put('a','5'); HEX_SWAP.put('5','a');
        HEX_SWAP.put('9','6'); HEX_SWAP.put('6','9');
        HEX_SWAP.put('8','7'); HEX_SWAP.put('7','8');
    }

    public static String decodeFhPassword(String ssid) {
        if (ssid == null || !ssid.toLowerCase().startsWith("fh")) return null;
        String suffix = ssid.substring(2).replaceAll("^_+", "");
        if (suffix.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("wlan");
        for (char c : suffix.toLowerCase().toCharArray()) {
            sb.append(HEX_SWAP.getOrDefault(c, c));
        }
        return sb.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        recyclerView = findViewById(R.id.recyclerView);
        progressBar  = findViewById(R.id.progressBar);
        statusText   = findViewById(R.id.statusText);
        scanBtn      = findViewById(R.id.scanBtn);
        autoBtn      = findViewById(R.id.autoBtn);

        adapter = new NetworkAdapter(networks, this::onNetworkClick);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        scanBtn.setOnClickListener(v -> startScan());
        autoBtn.setOnClickListener(v -> autoConnect());

        requestPermissions();
    }

    // ========== الصلاحيات ==========
    private void requestPermissions() {
        String[] perms = {
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CHANGE_NETWORK_STATE,
        };
        List<String> needed = new ArrayList<>();
        for (String p : perms)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                needed.add(p);
        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST);
    }

    // ========== المسح ==========
    private void startScan() {
        if (!wifiManager.isWifiEnabled()) {
            setStatus("⚠️ WiFi معطّل — يرجى تفعيله");
            return;
        }
        scanBtn.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        setStatus("جاري المسح...");

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                unregisterReceiver(this);
                processScanResults();
            }
        };
        registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        wifiManager.startScan();
    }

    private void processScanResults() {
        List<ScanResult> results = wifiManager.getScanResults();
        networks.clear();
        for (ScanResult r : results) {
            String ssid = r.SSID;
            if (ssid == null || ssid.isEmpty()) continue;
            int level = WifiManager.calculateSignalLevel(r.level, 100);
            String password = decodeFhPassword(ssid);
            networks.add(new NetworkItem(ssid, level, password));
        }
        Collections.sort(networks, (a, b) -> b.signal - a.signal);

        mainHandler.post(() -> {
            adapter.notifyDataSetChanged();
            progressBar.setVisibility(View.GONE);
            scanBtn.setEnabled(true);
            long fhCount = networks.stream().filter(n -> n.password != null).count();
            setStatus("تم اكتشاف " + networks.size() + " شبكة — منها " + fhCount + " شبكة fh_");
        });
    }

    // ========== الاتصال ==========
    private void onNetworkClick(NetworkItem net) {
        if (net.password == null) {
            Toast.makeText(this, "لا يمكن الاتصال التلقائي بهذه الشبكة", Toast.LENGTH_SHORT).show();
            return;
        }
        connectToNetwork(net.ssid, net.password);
    }

    private void autoConnect() {
        NetworkItem best = null;
        for (NetworkItem n : networks)
            if (n.password != null && (best == null || n.signal > best.signal))
                best = n;
        if (best == null) { setStatus("لا توجد شبكات fh_ — امسح أولاً"); return; }
        connectToNetwork(best.ssid, best.password);
    }

    private void connectToNetwork(String ssid, String password) {
        setStatus("⚡ جاري الاتصال بـ " + ssid + "...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectModern(ssid, password);
        } else {
            connectLegacy(ssid, password);
        }
    }

    private void connectModern(String ssid, String password) {
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
            .setSsid(ssid).setWpa2Passphrase(password).build();
        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier).build();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network network) {
                mainHandler.post(() -> setStatus("✅ تم الاتصال بـ " + ssid));
            }
            @Override public void onUnavailable() {
                mainHandler.post(() -> setStatus("❌ فشل الاتصال بـ " + ssid));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void connectLegacy(String ssid, String password) {
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + ssid + "\"";
        conf.preSharedKey = "\"" + password + "\"";
        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        int netId = wifiManager.addNetwork(conf);
        if (netId == -1) { setStatus("❌ فشل إضافة الشبكة"); return; }
        wifiManager.disconnect();
        wifiManager.enableNetwork(netId, true);
        wifiManager.reconnect();
        setStatus("✅ جاري الاتصال بـ " + ssid);
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    // ========== NetworkItem ==========
    public static class NetworkItem {
        public String ssid, password;
        public int signal;
        NetworkItem(String ssid, int signal, String password) {
            this.ssid = ssid; this.signal = signal; this.password = password;
        }
    }

    // ========== Adapter ==========
    public static class NetworkAdapter extends RecyclerView.Adapter<NetworkAdapter.VH> {
        private List<NetworkItem> items;
        private OnClickListener listener;
        interface OnClickListener { void onClick(NetworkItem item); }

        NetworkAdapter(List<NetworkItem> items, OnClickListener l) {
            this.items = items; this.listener = l;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_network, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            NetworkItem net = items.get(pos);
            h.ssid.setText((net.password != null ? "🔓 " : "🔒 ") + net.ssid);
            h.signal.setText(signalBars(net.signal) + "  " + net.signal + "%");
            if (net.password != null) {
                h.password.setVisibility(View.VISIBLE);
                h.password.setText("🔑 " + net.password);
            } else {
                h.password.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> listener.onClick(net));
        }

        @Override public int getItemCount() { return items.size(); }

        static String signalBars(int pct) {
            if (pct >= 75) return "▂▄▆█";
            if (pct >= 50) return "▂▄▆░";
            if (pct >= 25) return "▂▄░░";
            return "▂░░░";
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView ssid, signal, password;
            VH(View v) {
                super(v);
                ssid     = v.findViewById(R.id.netSsid);
                signal   = v.findViewById(R.id.netSignal);
                password = v.findViewById(R.id.netPassword);
            }
        }
    }
}
