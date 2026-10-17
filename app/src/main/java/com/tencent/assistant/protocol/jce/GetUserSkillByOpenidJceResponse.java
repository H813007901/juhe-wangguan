package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class GetUserSkillByOpenidJceResponse extends JceStruct {
    static ArrayList<SkillDetail> cache_skillDetails = new ArrayList<>();
    public String msg;
    public int ret;
    public ArrayList<SkillDetail> skillDetails;

    static {
        cache_skillDetails.add(new SkillDetail());
    }

    public GetUserSkillByOpenidJceResponse() {
        this.ret = 0;
        this.msg = "";
        this.skillDetails = null;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.ret = jceInputStream.read(this.ret, 0, true);
        this.msg = jceInputStream.readString(1, false);
        this.skillDetails = (ArrayList) jceInputStream.read(cache_skillDetails, 2, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.ret, 0);
        String str = this.msg;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        ArrayList<SkillDetail> arrayList = this.skillDetails;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 2);
        }
    }

    public GetUserSkillByOpenidJceResponse(int i, String str, ArrayList<SkillDetail> arrayList) {
        this.ret = i;
        this.msg = str;
        this.skillDetails = arrayList;
    }
}
