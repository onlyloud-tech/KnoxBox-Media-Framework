/* Copyright (c) 2026 Grymlock.
 * SPDX-License-Identifier: LicenseRef-KnoxBox-Original-Content
 */
package com.knoxboxgb;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

public final class KBMFrameTexture {
    private static final Map<String, Bundle> bundles = new HashMap<String, Bundle>();
    private static final Map<String, Boolean> disabled = new HashMap<String, Boolean>();
    private static final Map<String, String> status = new HashMap<String, String>();
    private static Class<?> textureClass;
    private static Constructor<?> textureCtorWHF;
    private static Method textureSetData;
    private static Method textureGetWidthHW;
    private static Method textureGetHeightHW;
    private static Class<?> renderThreadClass;
    private static Method queueInvokeOnRenderContext;
    private static final int BUFFER_COUNT = 3;

    private KBMFrameTexture() {
    }

    public static Object update(final String string, int[] nArray, int n, int n2) {
        if (string == null || nArray == null || n <= 0 || n2 <= 0) {
            return null;
        }
        if (Boolean.TRUE.equals(disabled.get(string))) {
            return null;
        }
        try {
            KBMFrameTexture.ensureReflection();
        }
        catch (Throwable throwable) {
            KBMFrameTexture.disable(string, "reflection_init_failed: " + KBMFrameTexture.summarize(throwable));
            return null;
        }
        Bundle bundle = bundles.get(string);
        if (bundle == null || bundle.w != n || bundle.h != n2) {
            bundle = KBMFrameTexture.createBundle(string, n, n2);
            if (bundle == null) {
                return null;
            }
            bundles.put(string, bundle);
        }
        bundle.bufIndex = (bundle.bufIndex + 1) % 3;
        ByteBuffer byteBuffer = bundle.buffers[bundle.bufIndex];
        byteBuffer.clear();
        int n3 = bundle.hwW * 4;
        for (int i = 0; i < n2; ++i) {
            byteBuffer.position(i * n3);
            int n4 = i * n;
            int n5 = n4 + n;
            if (n5 > nArray.length) {
                n5 = nArray.length;
            }
            for (int j = n4; j < n5; ++j) {
                int n6 = nArray[j];
                byteBuffer.put((byte)(n6 >> 16 & 0xFF));
                byteBuffer.put((byte)(n6 >> 8 & 0xFF));
                byteBuffer.put((byte)(n6 & 0xFF));
                byteBuffer.put((byte)-1);
            }
        }
        byteBuffer.position(0);
        byteBuffer.limit(bundle.hwW * bundle.hwH * 4);
        final Object object = bundle.texture;
        final ByteBuffer byteBuffer2 = byteBuffer;
        try {
            queueInvokeOnRenderContext.invoke(null, new Runnable(){

                @Override
                public void run() {
                    try {
                        textureSetData.invoke(object, byteBuffer2);
                    }
                    catch (Throwable throwable) {
                        status.put(string, "setData_runtime_failed: " + KBMFrameTexture.summarize(throwable));
                    }
                }
            });
            status.put(string, "ok");
            return bundle.texture;
        }
        catch (Throwable throwable) {
            KBMFrameTexture.disable(string, "queue_invoke_failed: " + KBMFrameTexture.summarize(throwable));
            return null;
        }
    }

    public static String getStatus(String string) {
        if (Boolean.TRUE.equals(disabled.get(string))) {
            String string2 = status.get(string);
            return "disabled" + (String)(string2 != null ? ": " + string2 : "");
        }
        String string3 = status.get(string);
        return string3 != null ? string3 : "uninitialized";
    }

    public static String getInfo(String string) {
        Bundle bundle = bundles.get(string);
        if (bundle == null) {
            return "none";
        }
        return bundle.w + "x" + bundle.h + " (hw " + bundle.hwW + "x" + bundle.hwH + ")";
    }

    public static void resetKey(String string) {
        disabled.remove(string);
        bundles.remove(string);
        status.remove(string);
    }

    private static void ensureReflection() throws Exception {
        if (textureClass != null) {
            return;
        }
        textureClass = Class.forName("zombie.core.textures.Texture");
        textureCtorWHF = textureClass.getConstructor(Integer.TYPE, Integer.TYPE, Integer.TYPE);
        textureSetData = textureClass.getMethod("setData", ByteBuffer.class);
        textureGetWidthHW = textureClass.getMethod("getWidthHW", new Class[0]);
        textureGetHeightHW = textureClass.getMethod("getHeightHW", new Class[0]);
        renderThreadClass = Class.forName("zombie.core.opengl.RenderThread");
        queueInvokeOnRenderContext = renderThreadClass.getMethod("queueInvokeOnRenderContext", Runnable.class);
    }

    private static Bundle createBundle(String string, int n, int n2) {
        try {
            Object obj = textureCtorWHF.newInstance(n, n2, 0);
            int n3 = (Integer)textureGetWidthHW.invoke(obj, new Object[0]);
            int n4 = (Integer)textureGetHeightHW.invoke(obj, new Object[0]);
            if (n3 < n) {
                n3 = n;
            }
            if (n4 < n2) {
                n4 = n2;
            }
            int n5 = n3 * n4 * 4;
            ByteBuffer[] byteBufferArray = new ByteBuffer[3];
            for (int i = 0; i < 3; ++i) {
                byteBufferArray[i] = ByteBuffer.allocateDirect(n5).order(ByteOrder.nativeOrder());
            }
            Bundle bundle = new Bundle();
            bundle.texture = obj;
            bundle.buffers = byteBufferArray;
            bundle.bufIndex = 0;
            bundle.w = n;
            bundle.h = n2;
            bundle.hwW = n3;
            bundle.hwH = n4;
            status.put(string, "created " + n + "x" + n2 + " (hw " + n3 + "x" + n4 + ", 3 buffers x " + n5 + " bytes)");
            return bundle;
        }
        catch (Throwable throwable) {
            KBMFrameTexture.disable(string, "ctor_failed: " + KBMFrameTexture.summarize(throwable));
            return null;
        }
    }

    private static void disable(String string, String string2) {
        disabled.put(string, Boolean.TRUE);
        status.put(string, string2);
        bundles.remove(string);
    }

    private static String summarize(Throwable throwable) {
        Throwable throwable2;
        for (throwable2 = throwable; throwable2.getCause() != null && throwable2.getCause() != throwable2; throwable2 = throwable2.getCause()) {
        }
        String string = throwable2.getClass().getSimpleName();
        String string2 = throwable2.getMessage();
        return string2 != null ? string + ": " + string2 : string;
    }

    private static final class Bundle {
        Object texture;
        ByteBuffer[] buffers;
        int bufIndex;
        int w;
        int h;
        int hwW;
        int hwH;

        private Bundle() {
        }
    }
}
