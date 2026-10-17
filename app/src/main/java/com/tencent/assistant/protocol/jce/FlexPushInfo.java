package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class FlexPushInfo extends JceStruct {
    static ArrayList<SettingInfo> cache_SettingInfo = new ArrayList<>();
    public ArrayList<SettingInfo> SettingInfo;
    public long timespan;
    public short version;

    static {
        cache_SettingInfo.add(new SettingInfo());
    }

    public FlexPushInfo() {
        this.version = (short) 0;
        this.SettingInfo = null;
        this.timespan = 0L;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.version = jceInputStream.read(this.version, 0, true);
        this.SettingInfo = (ArrayList) jceInputStream.read(cache_SettingInfo, 1, true);
        this.timespan = jceInputStream.read(this.timespan, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.version, 0);
        jceOutputStream.write((Collection) this.SettingInfo, 1);
        jceOutputStream.write(this.timespan, 2);
    }

    public FlexPushInfo(short s, ArrayList<SettingInfo> arrayList, long j2) {
        this.version = s;
        this.SettingInfo = arrayList;
        this.timespan = j2;
    }
}
