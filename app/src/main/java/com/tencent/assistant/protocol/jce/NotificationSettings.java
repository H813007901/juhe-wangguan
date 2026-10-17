package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class NotificationSettings extends JceStruct {
    public boolean allowConversationTaskCompletedNotification;
    public boolean allowConversationTaskPendingConfirmationNotification;
    public boolean allowScheduledTaskCompletedNotification;
    public boolean allowScheduledTaskPendingConfirmationNotification;
    public boolean allowSystemNotification;

    public NotificationSettings() {
        this.allowSystemNotification = false;
        this.allowConversationTaskCompletedNotification = false;
        this.allowScheduledTaskCompletedNotification = false;
        this.allowScheduledTaskPendingConfirmationNotification = false;
        this.allowConversationTaskPendingConfirmationNotification = false;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.allowSystemNotification = jceInputStream.read(this.allowSystemNotification, 0, true);
        this.allowConversationTaskCompletedNotification = jceInputStream.read(this.allowConversationTaskCompletedNotification, 1, true);
        this.allowScheduledTaskCompletedNotification = jceInputStream.read(this.allowScheduledTaskCompletedNotification, 2, true);
        this.allowScheduledTaskPendingConfirmationNotification = jceInputStream.read(this.allowScheduledTaskPendingConfirmationNotification, 3, true);
        this.allowConversationTaskPendingConfirmationNotification = jceInputStream.read(this.allowConversationTaskPendingConfirmationNotification, 4, true);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.allowSystemNotification, 0);
        jceOutputStream.write(this.allowConversationTaskCompletedNotification, 1);
        jceOutputStream.write(this.allowScheduledTaskCompletedNotification, 2);
        jceOutputStream.write(this.allowScheduledTaskPendingConfirmationNotification, 3);
        jceOutputStream.write(this.allowConversationTaskPendingConfirmationNotification, 4);
    }

    public NotificationSettings(boolean z2, boolean z3, boolean z4, boolean z5, boolean z6) {
        this.allowSystemNotification = z2;
        this.allowConversationTaskCompletedNotification = z3;
        this.allowScheduledTaskCompletedNotification = z4;
        this.allowScheduledTaskPendingConfirmationNotification = z5;
        this.allowConversationTaskPendingConfirmationNotification = z6;
    }
}
