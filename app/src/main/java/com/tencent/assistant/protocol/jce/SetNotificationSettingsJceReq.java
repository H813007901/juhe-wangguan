package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SetNotificationSettingsJceReq extends JceStruct {
    static NotificationSettings cache_settings = new NotificationSettings();
    public int platform;
    public NotificationSettings settings;

    public SetNotificationSettingsJceReq() {
        this.platform = 0;
        this.settings = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.settings = (NotificationSettings) jceInputStream.read((JceStruct) cache_settings, 1, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        jceOutputStream.write((JceStruct) this.settings, 1);
    }

    public SetNotificationSettingsJceReq(int i, NotificationSettings notificationSettings) {
        this.platform = i;
        this.settings = notificationSettings;
    }
}
