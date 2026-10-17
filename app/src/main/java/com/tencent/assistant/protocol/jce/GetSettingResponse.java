package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetSettingResponse extends JceStruct {
    static byte[] cache_context;
    static Map<String, String> cache_mapSettings;
    static ArrayList<SettingCfg> cache_settingCfg;
    public byte[] context;
    public Map<String, String> mapSettings;
    public int ret;
    public long serverTimestamp;
    public ArrayList<SettingCfg> settingCfg;

    public GetSettingResponse() {
        this.ret = 0;
        this.settingCfg = null;
        this.serverTimestamp = 0L;
        this.mapSettings = null;
        this.context = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        if (cache_settingCfg == null) {
            cache_settingCfg = new ArrayList<>();
            cache_settingCfg.add(new SettingCfg());
        }
        this.settingCfg = (ArrayList) jceInputStream.read(cache_settingCfg, 1, false);
        this.serverTimestamp = jceInputStream.read(this.serverTimestamp, 2, false);
        if (cache_mapSettings == null) {
            HashMap hashMap = new HashMap();
            cache_mapSettings = hashMap;
            hashMap.put("", "");
        }
        this.mapSettings = (Map) jceInputStream.read(cache_mapSettings, 3, false);
        if (cache_context == null) {
            cache_context = new byte[]{0};
        }
        this.context = jceInputStream.read(cache_context, 4, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        ArrayList<SettingCfg> arrayList = this.settingCfg;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 1);
        }
        jceOutputStream.write(this.serverTimestamp, 2);
        Map<String, String> map = this.mapSettings;
        if (map != null) {
            jceOutputStream.write((Map) map, 3);
        }
        byte[] bArr = this.context;
        if (bArr != null) {
            jceOutputStream.write(bArr, 4);
        }
    }

    public GetSettingResponse(int i, ArrayList<SettingCfg> arrayList, long j2, Map<String, String> map, byte[] bArr) {
        this.ret = i;
        this.settingCfg = arrayList;
        this.serverTimestamp = j2;
        this.mapSettings = map;
        this.context = bArr;
    }
}
