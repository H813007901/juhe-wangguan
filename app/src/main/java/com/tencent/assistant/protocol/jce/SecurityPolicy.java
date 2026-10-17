package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SecurityPolicy extends JceStruct {
    public int approvalStatus;
    public boolean hasSecurityPassword;

    public SecurityPolicy() {
        this.approvalStatus = 0;
        this.hasSecurityPassword = true;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.approvalStatus = jceInputStream.read(this.approvalStatus, 0, false);
        this.hasSecurityPassword = jceInputStream.read(this.hasSecurityPassword, 1, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.approvalStatus, 0);
        jceOutputStream.write(this.hasSecurityPassword, 1);
    }

    public SecurityPolicy(int i, boolean z2) {
        this.approvalStatus = i;
        this.hasSecurityPassword = z2;
    }
}
