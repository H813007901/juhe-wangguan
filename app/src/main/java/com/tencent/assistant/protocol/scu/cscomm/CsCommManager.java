package com.tencent.assistant.protocol.scu.cscomm;

import android.util.Log;

import com.tencent.assistant.protocol.jce.KeepAliveMsgBody;
import com.tencent.assistant.protocol.jce.PkgReq;
import com.tencent.assistant.protocol.jce.PkgRsp;
import com.tencent.assistant.protocol.jce.Request;
import com.tencent.assistant.protocol.jce.Response;

/**
 * 复刻自 Marvis 的同名类（JNI 按类全限定名绑定符号，类名/方法名/签名不可改）。
 * 仅替换日志为 android.util.Log，行为与反编译原文一致。
 */
public class CsCommManager {
    private static final String TAG = "cscomm";
    private static int authCommRet = -1;
    private static volatile CsCommManager instance = null;
    private static boolean loadSoSucc = false;

    static {
        try {
            Log.i(TAG, "start load yyb_csech");
            System.loadLibrary("yyb_csech");
            loadSoSucc = true;
        } catch (Throwable th) {
            loadSoSucc = false;
            Log.e(TAG, "load yyb_csech failed", th);
        }
    }

    public static native byte[] aes(String str, BytesObject bytesObject);

    public static native void clearAuthTicket();

    public static void clearAuthTicketWrap(byte b2) {
        Log.i(TAG, "clearAuthTicketWrap by type:" + ((int) b2));
        try {
            clearAuthTicket();
        } catch (Throwable th) {
            Log.e(TAG, "clearAuthTicketWrap failed", th);
        }
    }

    public static void csStatReport(int behaviorType, int behavior, int requestId, String msg) {
        Log.i(TAG, "csStatReport behaviorType=" + behaviorType
                + ", behavior=" + behavior + ", requestId=" + requestId + ", msg=" + msg);
    }

    public static int getAuthCommRet() {
        return authCommRet;
    }

    public static native int getAuthTicket(String str, long j2, String str2, String str3, String str4, BytesObject bytesObject, BytesObject bytesObject2);

    public static synchronized CsCommManager getInstance() {
        CsCommManager csCommManager;
        synchronized (CsCommManager.class) {
            if (instance == null) {
                instance = new CsCommManager();
            }
            csCommManager = instance;
        }
        return csCommManager;
    }

    public static native int getSessionId(BytesObject bytesObject);

    public static native byte[] hmac(byte[] bArr, int i);

    public static boolean isLoadSoSucc() {
        return loadSoSucc;
    }

    public static boolean isReady() {
        return loadSoSucc && authCommRet == 0;
    }

    public static native int startAuthComm(Object obj);

    public static void startAuthCommWrap(Object obj) {
        try {
            authCommRet = startAuthComm(obj);
            Log.i(TAG, "startAuthCommWrap ret:" + authCommRet);
        } catch (Throwable th) {
            authCommRet = -1;
            Log.e(TAG, "startAuthCommWrap failed", th);
        }
    }

    public static native void stopAuthComm();

    public native int decryptKeepAliveResponse(KeepAliveMsgBody keepAliveMsgBody, BytesObject bytesObject, byte b2);

    public native int decryptResponse(PkgRsp pkgRsp, Response response, byte b2);

    public native int encryptKeepAliveRequest(byte[] bArr, KeepAliveMsgBody keepAliveMsgBody, byte b2, String str);

    public native int encryptRequest(Request request, PkgReq pkgReq, byte b2, String str);

    public String getDictVer() {
        return "";
    }

    public void zstdChangeCompressType(int i) {
        Log.w(TAG, "zstdChangeCompressType ignored, compressType=" + i);
    }

    public byte[] zstdCompressData(byte[] bArr, int i) {
        Log.w(TAG, "zstdCompressData called but zstd is disabled");
        if (bArr == null) {
            return new byte[0];
        }
        return bArr;
    }

    public byte[] zstdDecompressData(byte[] bArr, int i) {
        Log.w(TAG, "zstdDecompressData called but zstd is disabled");
        if (bArr == null) {
            return new byte[0];
        }
        return bArr;
    }
}
