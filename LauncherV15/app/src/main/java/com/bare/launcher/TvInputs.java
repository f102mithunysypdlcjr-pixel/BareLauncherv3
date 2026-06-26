package com.bare.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Surfaces the device's hardware TV inputs (HDMI 1/2/3, AV / composite,
 * component, S-Video, …) as launcher entries via the platform TV Input
 * Framework (TIF) — the same mechanism stock TV launchers use.
 *
 * <h3>Why this needs no special permission</h3>
 * Enumerating inputs ({@link TvInputManager#getTvInputList()}) and switching
 * to one (an {@link Intent#ACTION_VIEW} on the input's passthrough channel
 * URI, handled by the system Live-TV app) are both available to an ordinary
 * app. There is no privileged / system permission involved; the manifest only
 * declares the {@code android.software.live_tv} feature as <em>not</em>
 * required so the APK still installs everywhere.
 *
 * <h3>Device support &amp; graceful degradation</h3>
 * Inputs only exist on hardware that actually has them — real Android TVs
 * with HDMI-in ports, and some TV boxes. Streaming sticks / Chromecast / most
 * cheap boxes / Fire TV expose no passthrough inputs, so {@link #enumerate}
 * returns an empty list and the launcher behaves exactly as before (no tiles,
 * no behaviour change). Every TIF call is wrapped so a misbehaving ROM can
 * never destabilise the shelf — failure simply yields "no inputs".
 *
 * <h3>Cost</h3>
 * {@link #enumerate} is a single binder query, run once per app-list scan on
 * the background app executor (never the UI thread). There is no callback, no
 * polling, and no steady-state cost. The generated input tile is cached in the
 * normal banner cache like any app tile.
 */
final class TvInputs {

    private TvInputs() { /* no instances */ }

    /**
     * Enumerate the device's passthrough TV inputs as {@link AppInfo} entries
     * (one per HDMI / AV / component port). Returns an empty list — never
     * {@code null} — on any device without TIF inputs or on any failure.
     *
     * <p>Safe to call off the main thread; it is invoked from the
     * {@code loadApps} PM scan on the app executor.
     */
    static List<AppInfo> enumerate(Context ctx) {
        List<AppInfo> out = new ArrayList<>();
        if (ctx == null) return out;
        try {
            TvInputManager tim =
                    (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return out;                 // no TIF on this device
            List<TvInputInfo> inputs = tim.getTvInputList();
            if (inputs == null) return out;
            for (TvInputInfo info : inputs) {
                if (info == null) continue;
                // Only passthrough inputs (HDMI / AV / component / …) are a
                // "switch to this source" tile. Tuner / app inputs are
                // channel-based and are intentionally skipped.
                if (!info.isPassthroughInput()) continue;
                // Respect OEM-hidden inputs (e.g. an unwired internal port).
                try { if (info.isHidden(ctx)) continue; } catch (Throwable ignored) { /* keep */ }
                String id = info.getId();
                if (id == null || id.isEmpty()) continue;
                out.add(AppInfo.tvInput(id, label(ctx, info)));
            }
        } catch (Throwable ignored) {
            // Any TIF failure → behave as if there are no inputs. The shelf
            // is never destabilised by a flaky TV-input service.
            out.clear();
        }
        return out;
    }

    /** Best user-visible label for an input: the OEM custom label ("HDMI 1")
     *  when present, then the generic input label, then a type-derived
     *  fallback. */
    private static String label(Context ctx, TvInputInfo info) {
        try {
            CharSequence custom = info.loadCustomLabel(ctx);
            if (custom != null && custom.length() > 0) return custom.toString();
        } catch (Throwable ignored) { /* fall through */ }
        try {
            CharSequence lbl = info.loadLabel(ctx);
            if (lbl != null && lbl.length() > 0) return lbl.toString();
        } catch (Throwable ignored) { /* fall through */ }
        switch (info.getType()) {
            case TvInputInfo.TYPE_HDMI:         return "HDMI";
            case TvInputInfo.TYPE_COMPONENT:    return "Component";
            case TvInputInfo.TYPE_COMPOSITE:    return "AV";
            case TvInputInfo.TYPE_SVIDEO:       return "S-Video";
            case TvInputInfo.TYPE_SCART:        return "SCART";
            case TvInputInfo.TYPE_VGA:          return "VGA";
            case TvInputInfo.TYPE_DVI:          return "DVI";
            case TvInputInfo.TYPE_DISPLAY_PORT: return "DisplayPort";
            case TvInputInfo.TYPE_TUNER:        return "Tuner";
            default:                            return "Input";
        }
    }

    /**
     * Switch to the given passthrough input by handing the system Live-TV app
     * an {@link Intent#ACTION_VIEW} on the input's channel URI. Returns
     * {@code true} if a handler was found and launched, {@code false} if no
     * app on the device can switch inputs (caller then shows a toast).
     *
     * @param anim optional launch-animation bundle (scale-up from the tile);
     *             may be {@code null}.
     */
    static boolean launch(Activity host, String inputId, Bundle anim) {
        if (host == null || inputId == null || inputId.isEmpty()) return false;
        try {
            Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
            Intent i = new Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(host.getPackageManager()) == null) return false;
            host.startActivity(i, anim);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
