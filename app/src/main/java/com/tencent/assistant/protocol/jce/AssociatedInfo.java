package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class AssociatedInfo extends JceStruct {
    public String deviceDesc;
    public String deviceId;
    public String deviceName;
    public String guid;
    public long heartbeat;
    public boolean isSupportBorderless;
    public boolean isSupportGamepad;
    public String openId;
    public String pcBorderlessVersion;
    public String pcKnowledgebaseVersion;
    public String pcMarvisVersion;
    public String pcQimei36;
    public int platform;
    public int status;

    public AssociatedInfo() {
        this.openId = "";
        this.guid = "";
        this.platform = 0;
        this.deviceName = "";
        this.deviceDesc = "";
        this.deviceId = "";
        this.status = 0;
        this.heartbeat = 0L;
        this.isSupportBorderless = false;
        this.isSupportGamepad = false;
        this.pcMarvisVersion = "";
        this.pcKnowledgebaseVersion = "";
        this.pcBorderlessVersion = "";
        this.pcQimei36 = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.openId = jceInputStream.readString(0, false);
        this.guid = jceInputStream.readString(1, false);
        this.platform = jceInputStream.read(this.platform, 2, false);
        this.deviceName = jceInputStream.readString(3, false);
        this.deviceDesc = jceInputStream.readString(4, false);
        this.deviceId = jceInputStream.readString(5, false);
        this.status = jceInputStream.read(this.status, 6, false);
        this.heartbeat = jceInputStream.read(this.heartbeat, 7, false);
        this.isSupportBorderless = jceInputStream.read(this.isSupportBorderless, 8, false);
        this.isSupportGamepad = jceInputStream.read(this.isSupportGamepad, 13, false);
        this.pcMarvisVersion = jceInputStream.readString(9, false);
        this.pcKnowledgebaseVersion = jceInputStream.readString(10, false);
        this.pcBorderlessVersion = jceInputStream.readString(11, false);
        this.pcQimei36 = jceInputStream.readString(12, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        String str = this.openId;
        if (str != null) {
            jceOutputStream.write(str, 0);
        }
        String str2 = this.guid;
        if (str2 != null) {
            jceOutputStream.write(str2, 1);
        }
        jceOutputStream.write(this.platform, 2);
        String str3 = this.deviceName;
        if (str3 != null) {
            jceOutputStream.write(str3, 3);
        }
        String str4 = this.deviceDesc;
        if (str4 != null) {
            jceOutputStream.write(str4, 4);
        }
        String str5 = this.deviceId;
        if (str5 != null) {
            jceOutputStream.write(str5, 5);
        }
        jceOutputStream.write(this.status, 6);
        jceOutputStream.write(this.heartbeat, 7);
        jceOutputStream.write(this.isSupportBorderless, 8);
        jceOutputStream.write(this.isSupportGamepad, 13);
        String str6 = this.pcMarvisVersion;
        if (str6 != null) {
            jceOutputStream.write(str6, 9);
        }
        String str7 = this.pcKnowledgebaseVersion;
        if (str7 != null) {
            jceOutputStream.write(str7, 10);
        }
        String str8 = this.pcBorderlessVersion;
        if (str8 != null) {
            jceOutputStream.write(str8, 11);
        }
        String str9 = this.pcQimei36;
        if (str9 != null) {
            jceOutputStream.write(str9, 12);
        }
    }

    public AssociatedInfo(String str, String str2, int i, String str3, String str4, String str5, int i2, long j2) {
        this.isSupportBorderless = false;
        this.isSupportGamepad = false;
        this.pcMarvisVersion = "";
        this.pcKnowledgebaseVersion = "";
        this.pcBorderlessVersion = "";
        this.pcQimei36 = "";
        this.openId = str;
        this.guid = str2;
        this.platform = i;
        this.deviceName = str3;
        this.deviceDesc = str4;
        this.deviceId = str5;
        this.status = i2;
        this.heartbeat = j2;
    }
}
