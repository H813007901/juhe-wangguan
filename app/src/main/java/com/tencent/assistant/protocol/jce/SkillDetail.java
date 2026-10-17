package com.tencent.assistant.protocol.jce;

import com.qq.taf.jce.JceInputStream;
import com.qq.taf.jce.JceOutputStream;
import com.qq.taf.jce.JceStruct;

/* compiled from: ProGuard */
/* loaded from: classes2.dex */
public final class SkillDetail extends JceStruct {
    public String author;
    public String author_icon;
    public String description;
    public String detail_describe;
    public String display_description;
    public String display_name;
    public int download_count;
    public String download_url;
    public String icon;
    public int id;
    public String last_modified_time;
    public String md5;
    public String name;
    public String scope;
    public int source;
    public int status;
    public String type;
    public int type_id;
    public String use_cases;
    public String version;

    public SkillDetail() {
        this.id = 0;
        this.name = "";
        this.description = "";
        this.display_name = "";
        this.display_description = "";
        this.detail_describe = "";
        this.use_cases = "";
        this.icon = "";
        this.type_id = 0;
        this.scope = "";
        this.version = "";
        this.md5 = "";
        this.download_url = "";
        this.download_count = 0;
        this.author = "";
        this.author_icon = "";
        this.source = 0;
        this.status = 0;
        this.last_modified_time = "";
        this.type = "";
    }

    @Override // com.qq.taf.jce.JceStruct
    public void readFrom(JceInputStream jceInputStream) {
        this.id = jceInputStream.read(this.id, 0, false);
        this.name = jceInputStream.readString(1, false);
        this.description = jceInputStream.readString(2, false);
        this.display_name = jceInputStream.readString(3, false);
        this.display_description = jceInputStream.readString(4, false);
        this.detail_describe = jceInputStream.readString(5, false);
        this.use_cases = jceInputStream.readString(6, false);
        this.icon = jceInputStream.readString(7, false);
        this.type_id = jceInputStream.read(this.type_id, 8, false);
        this.scope = jceInputStream.readString(9, false);
        this.version = jceInputStream.readString(10, true);
        this.md5 = jceInputStream.readString(11, false);
        this.download_url = jceInputStream.readString(12, false);
        this.download_count = jceInputStream.read(this.download_count, 13, false);
        this.author = jceInputStream.readString(14, false);
        this.author_icon = jceInputStream.readString(15, false);
        this.source = jceInputStream.read(this.source, 16, false);
        this.status = jceInputStream.read(this.status, 17, false);
        this.last_modified_time = jceInputStream.readString(18, false);
        this.type = jceInputStream.readString(19, false);
    }

    @Override // com.qq.taf.jce.JceStruct
    public void writeTo(JceOutputStream jceOutputStream) {
        jceOutputStream.write(this.id, 0);
        String str = this.name;
        if (str != null) {
            jceOutputStream.write(str, 1);
        }
        String str2 = this.description;
        if (str2 != null) {
            jceOutputStream.write(str2, 2);
        }
        String str3 = this.display_name;
        if (str3 != null) {
            jceOutputStream.write(str3, 3);
        }
        String str4 = this.display_description;
        if (str4 != null) {
            jceOutputStream.write(str4, 4);
        }
        String str5 = this.detail_describe;
        if (str5 != null) {
            jceOutputStream.write(str5, 5);
        }
        String str6 = this.use_cases;
        if (str6 != null) {
            jceOutputStream.write(str6, 6);
        }
        String str7 = this.icon;
        if (str7 != null) {
            jceOutputStream.write(str7, 7);
        }
        jceOutputStream.write(this.type_id, 8);
        String str8 = this.scope;
        if (str8 != null) {
            jceOutputStream.write(str8, 9);
        }
        jceOutputStream.write(this.version, 10);
        String str9 = this.md5;
        if (str9 != null) {
            jceOutputStream.write(str9, 11);
        }
        String str10 = this.download_url;
        if (str10 != null) {
            jceOutputStream.write(str10, 12);
        }
        jceOutputStream.write(this.download_count, 13);
        String str11 = this.author;
        if (str11 != null) {
            jceOutputStream.write(str11, 14);
        }
        String str12 = this.author_icon;
        if (str12 != null) {
            jceOutputStream.write(str12, 15);
        }
        jceOutputStream.write(this.source, 16);
        jceOutputStream.write(this.status, 17);
        String str13 = this.last_modified_time;
        if (str13 != null) {
            jceOutputStream.write(str13, 18);
        }
        String str14 = this.type;
        if (str14 != null) {
            jceOutputStream.write(str14, 19);
        }
    }

    public SkillDetail(int i, String str, String str2, String str3, String str4, String str5, String str6, String str7, int i2, String str8, String str9, String str10, String str11, int i3, String str12, String str13, int i4, int i5, String str14, String str15) {
        this.id = i;
        this.name = str;
        this.description = str2;
        this.display_name = str3;
        this.display_description = str4;
        this.detail_describe = str5;
        this.use_cases = str6;
        this.icon = str7;
        this.type_id = i2;
        this.scope = str8;
        this.version = str9;
        this.md5 = str10;
        this.download_url = str11;
        this.download_count = i3;
        this.author = str12;
        this.author_icon = str13;
        this.source = i4;
        this.status = i5;
        this.last_modified_time = str14;
        this.type = str15;
    }
}
