package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SetNotificationSettingsJceRsp extends JceStruct {
    static NotificationSettings cache_settings = new NotificationSettings();
    public String msg;
    public int ret;
    public NotificationSettings settings;

    public SetNotificationSettingsJceRsp() {
        this.ret = 0;
        this.msg = "";
        this.settings = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, true);
        this.settings = (NotificationSettings) jceInputStream.read((JceStruct) cache_settings, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        jceOutputStream.write(this.msg, 1);
        NotificationSettings notificationSettings = this.settings;
        if (notificationSettings != null) {
            jceOutputStream.write((JceStruct) notificationSettings, 2);
        }
    }

    public SetNotificationSettingsJceRsp(int i, String str, NotificationSettings notificationSettings) {
        this.ret = i;
        this.msg = str;
        this.settings = notificationSettings;
    }
}
