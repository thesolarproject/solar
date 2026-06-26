package com.solar.launcher.radio.fm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;

import com.solar.launcher.DebugAgentLog;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * MediaTek FM radio service bridge — reflection only so Solar builds without mtk stubs.
 * ponytail: binds {@code com.mediatek.FMRadio.IFMRadioService}; MHz floats per stock FMRadioService.
 */
public final class FmEngine {
  private static final String FM_PACKAGE = "com.mediatek.FMRadio";
  private static final String FM_SERVICE = "com.mediatek.FMRadio.FMRadioService";
  private static final String FM_SERVICE_ACTION = "com.mediatek.FMRadio.IFMRadioService";
  private static final long BIND_TIMEOUT_MS = 5000L;

  public interface ScanCallback {
    void onStationFound(int freqKhz, int signal, boolean stereo);

    void onScanComplete();

    void onError(String reason);
  }

  private final Context appCtx;
  private Object fmService;
  private ServiceConnection connection;
  private boolean bound;
  private ScanCallback scanCallback;

  public FmEngine(Context ctx) {
    appCtx = ctx.getApplicationContext();
  }

  /** True when mtk FM package is installed. */
  public boolean isAvailable() {
    try {
      appCtx.getPackageManager().getPackageInfo(FM_PACKAGE, 0);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Open device, power up, and tune — full FM playback start.
   * @return false when bind or vendor FM calls fail
   */
  public synchronized boolean playStation(int freqKhz) {
    float mhz = khzToMhz(freqKhz);
    // #region agent log
    try {
      DebugAgentLog.log(
          appCtx,
          "FmEngine.playStation",
          "start",
          "C",
          new JSONObject()
              .put("freqKhz", freqKhz)
              .put("mhz", mhz)
              .put("available", isAvailable()));
    } catch (Exception ignored) {}
    // #endregion
    if (!ensureBound()) {
      // #region agent log
      try {
        DebugAgentLog.log(
            appCtx, "FmEngine.playStation", "bind failed", "A", new JSONObject().put("mhz", mhz));
      } catch (Exception ignored) {}
      // #endregion
      return false;
    }
    if (!invokeBool("openDevice", new Class<?>[] {}, new Object[] {})) {
      // #region agent log
      DebugAgentLog.log(appCtx, "FmEngine.playStation", "openDevice failed", "A", null);
      // #endregion
      return false;
    }
    boolean powered = invokeBool("isPowerUp", new Class<?>[] {}, new Object[] {});
    boolean ok;
    if (powered) {
      ok = invokeBool("tune", new Class<?>[] {float.class}, new Object[] {mhz});
    } else {
      ok = invokeBool("powerUp", new Class<?>[] {float.class}, new Object[] {mhz});
    }
    // #region agent log
    try {
      DebugAgentLog.log(
          appCtx,
          "FmEngine.playStation",
          "vendor result",
          "C",
          new JSONObject().put("powered", powered).put("ok", ok).put("mhz", mhz));
    } catch (Exception ignored) {}
    // #endregion
    return ok;
  }

  public synchronized boolean openDevice() {
    return ensureBound() && invokeBool("openDevice", new Class<?>[] {}, new Object[] {});
  }

  public synchronized boolean powerUp(int freqKhz) {
    float mhz = khzToMhz(freqKhz);
    if (!ensureBound()) return false;
    if (!invokeBool("openDevice", new Class<?>[] {}, new Object[] {})) return false;
    return invokeBool("powerUp", new Class<?>[] {float.class}, new Object[] {mhz});
  }

  public synchronized boolean powerDown() {
    return invokeBool("powerDown", new Class<?>[] {}, new Object[] {});
  }

  public synchronized boolean tune(int freqKhz) {
    return invokeBool("tune", new Class<?>[] {float.class}, new Object[] {khzToMhz(freqKhz)});
  }

  public synchronized boolean mute(boolean muted) {
    Object result = invoke("setMute", new Class<?>[] {boolean.class}, new Object[] {muted});
    if (result instanceof Integer) return ((Integer) result) == 0;
    if (result instanceof Boolean) return (Boolean) result;
    return result != null;
  }

  public synchronized boolean startScan(final ScanCallback callback) {
    scanCallback = callback;
    if (!ensureBound()) {
      if (callback != null) callback.onError("FM service not bound");
      return false;
    }
    Object result = invoke("startScan", new Class<?>[] {}, new Object[] {});
    if (result instanceof int[]) {
      int[] freqs = (int[]) result;
      if (callback != null) {
        if (freqs.length == 0) {
          callback.onScanComplete();
        } else {
          for (int f : freqs) {
            callback.onStationFound(freqFromVendor(f), 0, false);
          }
          callback.onScanComplete();
        }
      }
      return true;
    }
    if (invokeBool("autoScan", new Class<?>[] {}, new Object[] {})) return true;
    if (callback != null) callback.onError("scan not supported");
    return false;
  }

  public synchronized void stopScan() {
    invoke("stopScan", new Class<?>[] {}, new Object[] {});
    invoke("cancelScan", new Class<?>[] {}, new Object[] {});
    scanCallback = null;
  }

  /** Program service name from RDS, or null when unavailable. */
  public synchronized String getRdsPs() {
    Object ps = invoke("getPS", new Class<?>[] {}, new Object[] {});
    if (ps instanceof String) {
      String s = ((String) ps).trim();
      return s.isEmpty() ? null : s;
    }
    ps = invoke("getPs", new Class<?>[] {}, new Object[] {});
    if (ps instanceof String) {
      String s = ((String) ps).trim();
      return s.isEmpty() ? null : s;
    }
    ps = invoke("getRadioText", new Class<?>[] {}, new Object[] {});
    if (ps instanceof String) {
      String s = ((String) ps).trim();
      return s.isEmpty() ? null : s;
    }
    return null;
  }

  public synchronized void release() {
    stopScan();
    powerDown();
    if (bound && connection != null) {
      try {
        appCtx.unbindService(connection);
      } catch (Exception ignored) {}
      bound = false;
      connection = null;
      fmService = null;
    }
  }

  private static float khzToMhz(int freqKhz) {
    return freqKhz / 1000f;
  }

  /** Stock service stores frequency * CONVERT_RATE (typically 10). */
  private static int freqFromVendor(int vendorFreq) {
    if (vendorFreq > 10000) return vendorFreq;
    if (vendorFreq > 1000) return vendorFreq * 10;
    return vendorFreq * 100;
  }

  private boolean ensureBound() {
    if (fmService != null) return true;
    // ponytail: never await bind on main — onServiceConnected is posted to main looper
    if (Looper.myLooper() == Looper.getMainLooper()) {
      final boolean[] ok = new boolean[] {false};
      final CountDownLatch done = new CountDownLatch(1);
      Thread binder =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  ok[0] = ensureBoundBlocking();
                  done.countDown();
                }
              },
              "FmEngineBind");
      binder.start();
      try {
        done.await(BIND_TIMEOUT_MS + 500L, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ignored) {}
      return ok[0];
    }
    return ensureBoundBlocking();
  }

  private boolean ensureBoundBlocking() {
    if (fmService != null) return true;
    synchronized (this) {
      if (fmService != null) return true;
      final CountDownLatch latch = new CountDownLatch(1);
      connection =
          new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
              try {
                fmService = asFmInterfaceInstance(binder);
                bound = true;
                // #region agent log
                try {
                  DebugAgentLog.log(
                      appCtx,
                      "FmEngine.onServiceConnected",
                      "bound",
                      "B",
                      new JSONObject()
                          .put("iface", fmService != null ? fmService.getClass().getName() : "null"));
                } catch (Exception ignored) {}
                // #endregion
              } catch (Exception e) {
                fmService = null;
                // #region agent log
                try {
                  DebugAgentLog.log(
                      appCtx,
                      "FmEngine.onServiceConnected",
                      "asInterface failed",
                      "A",
                      new JSONObject().put("err", e.getClass().getSimpleName()));
                } catch (Exception ignored) {}
                // #endregion
              } finally {
                latch.countDown();
              }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
              fmService = null;
              bound = false;
            }
          };
      try {
        Intent intent = new Intent(FM_SERVICE_ACTION);
        intent.setComponent(new ComponentName(FM_PACKAGE, FM_SERVICE));
        boolean requested = appCtx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        // #region agent log
        try {
          DebugAgentLog.log(
              appCtx,
              "FmEngine.ensureBound",
              "bindService",
              "B",
              new JSONObject().put("requested", requested));
        } catch (Exception ignored) {}
        // #endregion
        if (!requested) {
          connection = null;
          return false;
        }
        latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        // #region agent log
        try {
          DebugAgentLog.log(
              appCtx,
              "FmEngine.ensureBound",
              "bind exception",
              "B",
              new JSONObject().put("err", e.getClass().getSimpleName()));
        } catch (Exception ignored) {}
        // #endregion
        return false;
      }
      return fmService != null;
    }
  }

  private Object asFmInterfaceInstance(IBinder binder) throws Exception {
    Context fmCtx =
        appCtx.createPackageContext(
            FM_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
    Class<?> stub =
        Class.forName("com.mediatek.FMRadio.IFMRadioService$Stub", true, fmCtx.getClassLoader());
    Method asInterface = stub.getMethod("asInterface", IBinder.class);
    return asInterface.invoke(null, binder);
  }

  private Object invoke(String method, Class<?>[] types, Object[] args) {
    if (!ensureBound() || fmService == null) return null;
    try {
      Method m = fmService.getClass().getMethod(method, types);
      return m.invoke(fmService, args);
    } catch (Exception e) {
      // #region agent log
      try {
        DebugAgentLog.log(
            appCtx,
            "FmEngine.invoke",
            "failed",
            "A",
            new JSONObject().put("method", method).put("err", e.getClass().getSimpleName()));
      } catch (Exception ignored) {}
      // #endregion
      return null;
    }
  }

  private boolean invokeBool(String method, Class<?>[] types, Object[] args) {
    Object result = invoke(method, types, args);
    if (result instanceof Boolean) return (Boolean) result;
    if (result instanceof Integer) return ((Integer) result) >= 0;
    return result != null;
  }
}
