package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetAccountDeviceInfoJceResponse extends JceStruct {
    static AccountDeviceInfo cache_accountDeviceInfo = new AccountDeviceInfo();
    public AccountDeviceInfo accountDeviceInfo;
    public boolean hasAccessPassword;
    public String msg;
    public int ret;

    public GetAccountDeviceInfoJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.accountDeviceInfo = null;
        this.hasAccessPassword = true;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.accountDeviceInfo = (AccountDeviceInfo) jceInputStream.read((JceStruct) cache_accountDeviceInfo, 2, false);
        this.hasAccessPassword = jceInputStream.read(this.hasAccessPassword, 3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        AccountDeviceInfo accountDeviceInfo = this.accountDeviceInfo;
        if (accountDeviceInfo != null) {
            jceOutputStream.write((JceStruct) accountDeviceInfo, 2);
        }
        jceOutputStream.write(this.hasAccessPassword, 3);
    }

    public GetAccountDeviceInfoJceResponse(int i, String str, AccountDeviceInfo accountDeviceInfo, boolean z2) {
        this.ret = i;
        this.msg = str;
        this.accountDeviceInfo = accountDeviceInfo;
        this.hasAccessPassword = z2;
    }
}
