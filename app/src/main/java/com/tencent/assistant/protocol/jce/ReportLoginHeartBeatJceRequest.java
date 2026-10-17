package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.HashMap;
import java.util.Map;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class ReportLoginHeartBeatJceRequest extends JceStruct {
    static Map<String, String> cache_extMap;
    public Map<String, String> extMap;
    public int loginTime;
    public int platform;

    public ReportLoginHeartBeatJceRequest() {
        this.platform = 0;
        this.loginTime = 0;
        this.extMap = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.loginTime = jceInputStream.read(this.loginTime, 1, true);
        if (cache_extMap == null) {
            HashMap hashMap = new HashMap();
            cache_extMap = hashMap;
            hashMap.put("", "");
        }
        this.extMap = (Map) jceInputStream.read(cache_extMap, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        jceOutputStream.write(this.loginTime, 1);
        Map<String, String> map = this.extMap;
        if (map != null) {
            jceOutputStream.write((Map) map, 2);
        }
    }

    public ReportLoginHeartBeatJceRequest(int i, int i2) {
        this.extMap = null;
        this.platform = i;
        this.loginTime = i2;
    }

    public ReportLoginHeartBeatJceRequest(int i, int i2, Map<String, String> map) {
        this.platform = i;
        this.loginTime = i2;
        this.extMap = map;
    }
}
