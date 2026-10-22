package com.sevenxu.eq;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.sevenxu.eq.AppLog;

/**
 * 定位這一路：經緯度，以及（車輛屬性給不出車速時）GPS 推算的車速。
 *
 * <p>要 {@code ACCESS_FINE_LOCATION}。開信息條時設置頁會去要這個權限；
 * 沒給就只記一句「沒有定位權限」，其餘信號照常。容器裏定位到底給不給、給的是不是車機的
 * GPS，沒有實測過 —— 這一路本身就是試驗的一部分，結果看黑匣子。</p>
 */
final class LocationSource implements LocationListener {

    private static final String TAG = "LocationSource";
    /** 多久要一次；信息條 5 次/秒重畫，定位半秒一次足夠。 */
    private static final long MIN_INTERVAL_MS = 500L;

    private final Telemetry telemetry;
    private LocationManager manager;
    private HandlerThread thread;
    private volatile String status = "not started";

    LocationSource(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    void start(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            status = "no permission";
            telemetry.sourceReported("location", status);
            return;
        }
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            status = "no service";
            telemetry.sourceReported("location", status);
            return;
        }
        thread = new HandlerThread("Telemetry-Location");
        thread.start();
        StringBuilder subscribed = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) {
                    failed.append(provider).append("=off ");
                    continue;
                }
                manager.requestLocationUpdates(provider, MIN_INTERVAL_MS, 0f, this, thread.getLooper());
                subscribed.append(provider).append(' ');
                Location last = manager.getLastKnownLocation(provider);
                if (last != null) {
                    onLocationChanged(last);
                }
            } catch (Exception e) {
                // SecurityException（權限）、IllegalArgumentException（沒有這個提供者）都算連不上
                failed.append(provider).append('=').append(e.getClass().getSimpleName()).append(' ');
                AppLog.w(TAG, "訂閲 " + provider + " 失敗: " + e);
            }
        }
        status = subscribed.length() > 0 ? "subscribed " + subscribed.toString().trim()
                : "no provider";
        if (failed.length() > 0) {
            status += " (" + failed.toString().trim() + ")";
        }
        telemetry.sourceReported("location", status);
    }

    void stop() {
        LocationManager m = manager;
        manager = null;
        if (m != null) {
            try {
                m.removeUpdates(this);
            } catch (Exception e) {
                AppLog.w(TAG, "取消定位訂閲失敗: " + e);
            }
        }
        HandlerThread t = thread;
        thread = null;
        if (t != null) {
            t.quitSafely();
        }
        status = "stopped";
    }

    String status() {
        return status;
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        final double lat = location.getLatitude();
        final double lon = location.getLongitude();
        // 後備條件是「車機現在還有沒有在報」，不是「曾經有沒有」——
        // 車機一斷就讓 GPS 頂上，車速牌才不會變 --（用户 2026-10-08 問的正是這個）。
        // 而 GPS 自己沒有速度欄位時用 0（用户 2026-10-08：「GPS 無數值就用 0」）——
        // 泊車、慢速時 GPS 正是這樣報，寫 0 比讓牌子變 -- 更貼近實況
        final boolean fillIn = Telemetry.gpsSpeedShouldFillIn(telemetry.carSpeedFresh());
        if (fillIn) {
            final float kmh = Telemetry.gpsSpeedKmh(location.hasSpeed(), location.getSpeed());
            // GPS 這一步真的寫了車速，記下時刻：車速牌超過 5 秒沒新讀數就畫 --
            telemetry.noteReading(Signal.SPEED);
            telemetry.edit(b -> {
                b.position(lat, lon);
                b.speedKmh(kmh);
            });
        } else {
            telemetry.edit(b -> b.position(lat, lon));
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }
}
