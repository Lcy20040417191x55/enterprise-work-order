# -*- coding: utf-8 -*-
# 读取 .xlsx 并把首张表转成 JSON 输出，供导出的端到端测试做断言。
# 只依赖标准库 zipfile + xml，不引入 openpyxl，避免测试环境多一个安装步骤。
import sys, json, zipfile, re

NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

def load_shared(z):
    out = []
    if "xl/sharedStrings.xml" not in z.namelist():
        return out
    xml = z.read("xl/sharedStrings.xml").decode("utf-8")
    for si in re.findall(r"<si>(.*?)</si>", xml, re.S):
        out.append("".join(re.findall(r"<t[^>]*>(.*?)</t>", si, re.S)))
    return out

def cell_text(c, shared):
    t = c.get("t")
    if t == "inlineStr":
        m = re.search(r"<is>(.*?)</is>", c.text, re.S) if c.text else None
        if m:
            return "".join(re.findall(r"<t[^>]*>(.*?)</t>", m.group(1), re.S))
        return ""
    v = re.search(r"<v>(.*?)</v>", c.text, re.S) if c.text else None
    if not v:
        return ""
    if t == "s":
        return shared[int(v.group(1))]
    return v.group(1)

def unescape(s):
    return (s.replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", '"').replace("&apos;", "'").replace("&amp;", "&"))

path = sys.argv[1]
z = zipfile.ZipFile(path)
shared = load_shared(z)
xml = z.read("xl/worksheets/sheet1.xml").decode("utf-8")
# 逐行解析：<row r="N"> ... </row>
rows = []
for rnum, body in re.findall(r'<row[^>]*r="(\d+)"[^>]*>(.*?)</row>', xml, re.S):
    cells = []
    for cm in re.finditer(r"<c\b[^>]*>.*?</c>|<c\b[^>]*/>", body, re.S):
        raw = cm.group(0)
        attrs = re.match(r"<c\b([^>]*)", raw).group(1)
        t = re.search(r't="([^"]+)"', attrs)
        t = t.group(1) if t else None
        if t == "inlineStr":
            inner = re.search(r"<is>(.*?)</is>", raw, re.S)
            val = "".join(re.findall(r"<t[^>]*>(.*?)</t>", inner.group(1), re.S)) if inner else ""
        else:
            v = re.search(r"<v>(.*?)</v>", raw, re.S)
            val = "" if not v else (shared[int(v.group(1))] if t == "s" else v.group(1))
        cells.append(unescape(val))
    rows.append({"row": int(rnum), "cells": cells})
print(json.dumps({"rows": len(rows), "sheet": rows}, ensure_ascii=False))
