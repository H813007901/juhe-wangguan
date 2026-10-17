package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class LogDownloadInfo extends JceStruct {
    public String downloadURL;
    public String guid;
    public String openID;

    public LogDownloadInfo() {
        this.guid = "";
        this.openID = "";
        this.downloadURL = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.guid = jceInputStream.readString(0, true);
        this.openID = jceInputStream.readString(1, false);
        this.downloadURL = jceInputStream.readString(2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.guid, 0);
        String str = this.openID;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        String str2 = this.downloadURL;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
    }

    public LogDownloadInfo(String str, String str2, String str3) {
        this.guid = str;
        this.openID = str2;
        this.downloadURL = str3;
    }
}
