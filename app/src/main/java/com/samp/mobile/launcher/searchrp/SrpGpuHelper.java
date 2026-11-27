package com.samp.mobile.launcher.searchrp;

import android.opengl.GLES20;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Offscreen OpenGL ES 2.0 GPU texture-format detector (DXT / ETC / PVR).
 * Maps device GPU extensions directly to GTA SA Android 2.10 texdb suffixes:
 * - PowerVR -> "pvr"
 * - Adreno / Tegra (DXT/S3TC) -> "dxt"
 * - Mali / Exynos / fallback -> "etc"
 */
public final class SrpGpuHelper {

    public static class GpuInfo {
        public final String ext;       // "dxt", "etc", or "pvr"
        public final String renderer;  // e.g. "PowerVR Rogue GE8322", "Adreno (TM) 610"
        public final String label;     // e.g. "PowerVR (.pvr)"

        public GpuInfo(String ext, String renderer, String label) {
            this.ext = ext;
            this.renderer = renderer;
            this.label = label;
        }
    }

    private static volatile GpuInfo cached = null;

    public static GpuInfo detect() {
        if (cached != null) return cached;

        String ext = "etc";
        String renderer = "Unknown GPU";

        EGL10 egl = null;
        EGLDisplay display = EGL10.EGL_NO_DISPLAY;
        EGLContext context = EGL10.EGL_NO_CONTEXT;
        EGLSurface surface = EGL10.EGL_NO_SURFACE;
        try {
            egl = (EGL10) EGLContext.getEGL();
            display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (display != EGL10.EGL_NO_DISPLAY && egl.eglInitialize(display, version)) {
                int[] cfgAttr = new int[]{
                        EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                        EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                        EGL10.EGL_RED_SIZE, 8,
                        EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] numCfg = new int[1];
                if (egl.eglChooseConfig(display, cfgAttr, configs, 1, numCfg) && numCfg[0] > 0) {
                    int[] ctxAttr = new int[]{0x3098, 2, EGL10.EGL_NONE}; // EGL_CONTEXT_CLIENT_VERSION = 2
                    context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, ctxAttr);
                    int[] surfAttr = new int[]{EGL10.EGL_WIDTH, 16, EGL10.EGL_HEIGHT, 16, EGL10.EGL_NONE};
                    surface = egl.eglCreatePbufferSurface(display, configs[0], surfAttr);
                    if (context != null && context != EGL10.EGL_NO_CONTEXT
                            && surface != null && surface != EGL10.EGL_NO_SURFACE
                            && egl.eglMakeCurrent(display, surface, surface, context)) {
                        String glExt = GLES20.glGetString(GLES20.GL_EXTENSIONS);
                        String glRen = GLES20.glGetString(GLES20.GL_RENDERER);
                        if (glRen != null && !glRen.trim().isEmpty()) {
                            renderer = glRen.trim();
                        }
                        String extLow = glExt == null ? "" : glExt.toLowerCase();
                        String renLow = renderer.toLowerCase();

                        if (extLow.contains("gl_img_texture_compression_pvrtc") || renLow.contains("powervr")) {
                            ext = "pvr";
                        } else if (extLow.contains("gl_ext_texture_compression_dxt1")
                                || extLow.contains("gl_ext_texture_compression_s3tc")
                                || extLow.contains("gl_amd_compressed_atc_texture")
                                || renLow.contains("adreno")
                                || renLow.contains("tegra")) {
                            ext = "dxt";
                        } else {
                            ext = "etc";
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (egl != null && display != EGL10.EGL_NO_DISPLAY) {
                    egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                    if (surface != EGL10.EGL_NO_SURFACE) egl.eglDestroySurface(display, surface);
                    if (context != EGL10.EGL_NO_CONTEXT) egl.eglDestroyContext(display, context);
                    egl.eglTerminate(display);
                }
            } catch (Exception ignored) {
            }
        }

        String label;
        if ("pvr".equals(ext)) {
            label = "PowerVR (.pvr)";
        } else if ("dxt".equals(ext)) {
            label = "Adreno / DXT (.dxt)";
        } else {
            label = "Mali / ETC (.etc)";
        }
        cached = new GpuInfo(ext, renderer, label);
        return cached;
    }
}
