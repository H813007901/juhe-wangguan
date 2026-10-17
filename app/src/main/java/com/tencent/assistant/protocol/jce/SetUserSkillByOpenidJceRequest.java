package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;
import java.util.ArrayList;
import java.util.Collection;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SetUserSkillByOpenidJceRequest extends JceStruct {
    static ArrayList<SkillDetail> cache_skillDetails = new ArrayList<>();
    public String pcGuid;
    public int platform;
    public int setType;
    public ArrayList<SkillDetail> skillDetails;

    static {
        cache_skillDetails.add(new SkillDetail());
    }

    public SetUserSkillByOpenidJceRequest() {
        this.platform = 0;
        this.pcGuid = "";
        this.skillDetails = null;
        this.setType = 0;
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.platform = jceInputStream.read(this.platform, 0, true);
        this.pcGuid = jceInputStream.readString(1, true);
        this.skillDetails = (ArrayList) jceInputStream.read(cache_skillDetails, 2, false);
        this.setType = jceInputStream.read(this.setType, 3, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.platform, 0);
        jceOutputStream.write(this.pcGuid, 1);
        ArrayList<SkillDetail> arrayList = this.skillDetails;
        if (arrayList != null) {
            jceOutputStream.write((Collection) arrayList, 2);
        }
        jceOutputStream.write(this.setType, 3);
    }

    public SetUserSkillByOpenidJceRequest(int i, String str, ArrayList<SkillDetail> arrayList, int i2) {
        this.platform = i;
        this.pcGuid = str;
        this.skillDetails = arrayList;
        this.setType = i2;
    }
}
