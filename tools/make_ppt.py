# -*- coding: utf-8 -*-
"""按课终答辩模板生成汇报 PPT。

用法：python tools/make_ppt.py
输出：E:\\WorkBuddy\\操作系统综合实验\\课终答辩-端侧人脸识别流水线.pptx

设计约定
- 复用模板母版与版式：封面1 / 目录页（4） / 过渡页1-4 / 内容页1 / 结尾页2
- 主色 #1D2974（模板藏青），强调 #4156D0，浅底 #EEF1F9，灰 #B9C2D3
- 中文字体 微软雅黑，代码 Consolas
- 需要截图的页面放虚线占位框，后期直接替换图片
"""
import os
import shutil

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Emu, Inches, Pt

TEMPLATE = r"E:\longly\操作系统\PPT\课终答辩.pptx"
OUT_DIR = r"E:\WorkBuddy\操作系统综合实验"
OUT_NAME = "课终答辩-端侧人脸识别流水线.pptx"

NAVY = RGBColor(0x1D, 0x29, 0x74)
BLUE = RGBColor(0x41, 0x56, 0xD0)
LIGHT = RGBColor(0xEE, 0xF1, 0xF9)
LIGHT2 = RGBColor(0xF7, 0xF8, 0xFC)
GRAY = RGBColor(0xB9, 0xC2, 0xD3)
TEXT = RGBColor(0x2B, 0x2B, 0x2B)
MUTED = RGBColor(0x76, 0x7E, 0x94)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
GREEN = RGBColor(0x2E, 0x9E, 0x6E)
RED = RGBColor(0xC0, 0x3A, 0x3A)

CN = "微软雅黑"
MONO = "Consolas"

L, R = 0.55, 12.78          # 正文左右边界（英寸）
TOP, BOT = 1.05, 6.82       # 正文上下边界


# ---------------------------------------------------------------- 基础工具

def clear_slides(prs):
    sld_id_lst = prs.slides._sldIdLst
    for sld in list(sld_id_lst):
        prs.part.drop_rel(sld.get(qn("r:id")))
        sld_id_lst.remove(sld)


def _fmt_run(run, size, bold, color, font=CN, italic=False):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.name = font
    run.font.color.rgb = color
    run.font.italic = italic
    # 中文字体要单独指定，否则 PowerPoint 用主题东亚字体
    rPr = run._r.get_or_add_rPr()
    ea = rPr.find(qn("a:ea"))
    if ea is None:
        ea = rPr.makeelement(qn("a:ea"), {})
        rPr.append(ea)
    ea.set("typeface", font)


def tb(slide, x, y, w, h, lines, size=14, color=TEXT, bold=False,
       align=PP_ALIGN.LEFT, font=CN, anchor=MSO_ANCHOR.TOP,
       line_spacing=1.25, space_after=2, margins=(0.02, 0.02, 0.02, 0.02)):
    """lines: 字符串 或 [(文本, size, bold, color), ...] 或 混合列表"""
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    tf.margin_left = Inches(margins[0])
    tf.margin_right = Inches(margins[1])
    tf.margin_top = Inches(margins[2])
    tf.margin_bottom = Inches(margins[3])
    if isinstance(lines, str):
        lines = [lines]
    for i, item in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.line_spacing = line_spacing
        p.space_after = Pt(space_after)
        if isinstance(item, tuple):
            text, s, b, c = (item + (size, bold, color))[:4]
        else:
            text, s, b, c = item, size, bold, color
        run = p.add_run()
        run.text = text
        _fmt_run(run, s, b, c, font)
    return box


def rect(slide, x, y, w, h, fill=None, line=None, line_w=1.0,
         shape=MSO_SHAPE.RECTANGLE, dash=None, radius=None):
    sh = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    if fill is None:
        sh.fill.background()
    else:
        sh.fill.solid()
        sh.fill.fore_color.rgb = fill
    if line is None:
        sh.line.fill.background()
    else:
        sh.line.color.rgb = line
        sh.line.width = Pt(line_w)
        if dash is not None:
            sh.line.dash_style = dash
    sh.shadow.inherit = False
    if radius is not None and shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        sh.adjustments[0] = radius
    if sh.has_text_frame:
        sh.text_frame.word_wrap = True
        sh.text_frame.margin_left = Inches(0.05)
        sh.text_frame.margin_right = Inches(0.05)
        sh.text_frame.margin_top = Inches(0.02)
        sh.text_frame.margin_bottom = Inches(0.02)
    return sh


def shape_text(sh, lines, size=12, color=TEXT, bold=False,
               align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE, font=CN,
               line_spacing=1.1):
    tf = sh.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    if isinstance(lines, str):
        lines = [lines]
    for i, item in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.line_spacing = line_spacing
        p.space_after = Pt(0)
        if isinstance(item, tuple):
            text, s, b, c = (item + (size, bold, color))[:4]
        else:
            text, s, b, c = item, size, bold, color
        run = p.add_run()
        run.text = text
        _fmt_run(run, s, b, c, font)
    return sh


def arrow(slide, x1, y1, x2, y2, color=BLUE, width=1.75, dash=None):
    from pptx.enum.shapes import MSO_CONNECTOR
    conn = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1),
                                      Inches(x2), Inches(y2))
    conn.line.color.rgb = color
    conn.line.width = Pt(width)
    if dash is not None:
        conn.line.dash_style = dash
    ln = conn.line._get_or_add_ln()
    tail = ln.makeelement(qn("a:tailEnd"), {"type": "triangle", "w": "med", "len": "med"})
    ln.append(tail)
    return conn


def title(slide, text, sub=None):
    """内容页标题：占位符 + 下方一句话结论（放在版式分隔线之上）"""
    ph = slide.shapes.title
    ph.left, ph.top = Inches(0.95), Inches(0.20)
    ph.width, ph.height = Inches(10.6), Inches(0.46)
    ph.text_frame.vertical_anchor = MSO_ANCHOR.MIDDLE
    tf = ph.text_frame
    tf.word_wrap = True
    tf.text = ""
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.LEFT
    run = p.add_run()
    run.text = text
    _fmt_run(run, 23, True, NAVY)
    if sub:
        tb(slide, 0.98, 0.69, 11.4, 0.24, sub, size=10.5, color=MUTED, font=CN)


def img_placeholder(slide, x, y, w, h, label, note=None):
    from pptx.enum.dml import MSO_LINE_DASH_STYLE
    sh = rect(slide, x, y, w, h, fill=LIGHT2, line=GRAY, line_w=1.25, dash=MSO_LINE_DASH_STYLE.DASH)
    lines = [("【 截图占位 】", 12, False, MUTED), (label, 12.5, True, MUTED)]
    if note:
        lines.append((note, 10, False, GRAY))
    shape_text(sh, lines, size=12, color=MUTED)


def table(slide, x, y, w, h, data, col_ratio, font_size=11.5,
          header_size=None, row_h=None, align_first_left=True, header_fill=NAVY):
    rows, cols = len(data), len(data[0])
    gf = slide.shapes.add_table(rows, cols, Inches(x), Inches(y), Inches(w), Inches(h))
    tbl = gf.table
    # 去掉模板表格样式，靠填充色区分
    tblPr = tbl._tbl.tblPr
    for e in tblPr.findall(qn("a:tableStyleId")):
        tblPr.remove(e)
    total = sum(col_ratio)
    for i, r in enumerate(col_ratio):
        tbl.columns[i].width = Emu(int(Inches(w) * r / total))
    if row_h:
        for i in range(rows):
            tbl.rows[i].height = Inches(row_h[i] if isinstance(row_h, list) else row_h)
    header_size = header_size or font_size
    for ri, row in enumerate(data):
        for ci, val in enumerate(row):
            cell = tbl.cell(ri, ci)
            cell.margin_left = Inches(0.09)
            cell.margin_right = Inches(0.06)
            cell.margin_top = Inches(0.03)
            cell.margin_bottom = Inches(0.03)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.fill.solid()
            if ri == 0:
                cell.fill.fore_color.rgb = header_fill
            else:
                cell.fill.fore_color.rgb = WHITE if ri % 2 == 1 else LIGHT2
            tf = cell.text_frame
            tf.word_wrap = True
            tf.text = ""
            for li, line in enumerate(str(val).split("\n")):
                p = tf.paragraphs[0] if li == 0 else tf.add_paragraph()
                p.line_spacing = 1.05
                p.space_after = Pt(0)
                if ci == 0 and align_first_left and ri > 0:
                    p.alignment = PP_ALIGN.LEFT
                else:
                    p.alignment = PP_ALIGN.CENTER if ci > 0 or ri == 0 else PP_ALIGN.LEFT
                run = p.add_run()
                run.text = line
                if ri == 0:
                    _fmt_run(run, header_size, True, WHITE)
                else:
                    _fmt_run(run, font_size, ci == 0, TEXT)
    return tbl


def code_box(slide, x, y, w, h, code_lines, size=10.5):
    sh = rect(slide, x, y, w, h, fill=RGBColor(0xF4, 0xF6, 0xFB), line=RGBColor(0xD8, 0xDE, 0xEE))
    tf = sh.text_frame
    tf.word_wrap = False
    tf.vertical_anchor = MSO_ANCHOR.TOP
    tf.margin_left = Inches(0.12)
    tf.margin_top = Inches(0.08)
    for i, line in enumerate(code_lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = PP_ALIGN.LEFT
        p.line_spacing = 1.15
        p.space_after = Pt(0)
        run = p.add_run()
        run.text = line
        _fmt_run(run, size, False, RGBColor(0x33, 0x3A, 0x4D), MONO)
    return sh


def bullet_block(slide, x, y, w, h, items, size=13, gap=6, marker_color=BLUE):
    """带小方块标记的要点列表；items 为 (标题, 说明) 或 纯字符串"""
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    tf.margin_left = 0
    tf.margin_top = 0
    for i, item in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.line_spacing = 1.32
        p.space_after = Pt(gap)
        if isinstance(item, tuple):
            head, body = item
            r0 = p.add_run()
            r0.text = "▍"
            _fmt_run(r0, size, True, marker_color)
            r1 = p.add_run()
            r1.text = head
            _fmt_run(r1, size, True, NAVY)
            if body:
                r2 = p.add_run()
                r2.text = "  " + body
                _fmt_run(r2, size - 1, False, TEXT)
        else:
            r0 = p.add_run()
            r0.text = "▍"
            _fmt_run(r0, size, True, marker_color)
            r1 = p.add_run()
            r1.text = item
            _fmt_run(r1, size, False, TEXT)
    return box


# ---------------------------------------------------------------- 各页

def campus_photo():
    """从模板压缩包里取出校园照，作为封面配图（避免仓库里多存一份 5MB 图片）"""
    import zipfile
    dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".tmp_model", "campus_gate.png")
    dst = os.path.abspath(dst)
    if not os.path.exists(dst):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with zipfile.ZipFile(TEMPLATE) as z:
            with open(dst, "wb") as f:
                f.write(z.read("ppt/media/image5.png"))
    return dst


def slide_cover(prs, layout):
    s = prs.slides.add_slide(layout)
    s.shapes.title.text = ""
    tf = s.shapes.title.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.LEFT
    p.line_spacing = 1.18
    r = p.add_run()
    r.text = "端侧人脸识别与活体检测流水线"
    _fmt_run(r, 32, True, WHITE)

    # 副标题（放在标题框外，避免压住模板的分隔线）
    tb(s, 0.74, 4.22, 6.6, 0.36,
       "三模型部署 · 双进程隔离 · Binder IPC · 性能观测",
       size=14, color=RGBColor(0xD5, 0xDA, 0xEE))

    # 右侧校园照（等比裁切填满，再叠一个藏青三角做出斜切边）
    try:
        pic = s.shapes.add_picture(campus_photo(), Inches(7.28), Inches(2.08),
                                   width=Inches(6.07), height=Inches(4.04))
        clip = (1 - (6.07 / 4.04) / (2889 / 1129)) / 2
        pic.crop_left = clip
        pic.crop_right = clip
        ff = s.shapes.build_freeform(Inches(7.28), Inches(2.08))
        ff.add_line_segments([(Inches(8.12), Inches(2.08)),
                              (Inches(7.28), Inches(6.12))], close=True)
        tri = ff.convert_to_shape()
        tri.fill.solid()
        tri.fill.fore_color.rgb = NAVY
        tri.line.fill.background()
        tri.shadow.inherit = False
    except Exception as e:
        print("封面配图跳过:", e)

    # 答辩人 / 指导老师
    smalls = [sh for sh in s.placeholders if sh.has_text_frame
              and sh.height < Inches(0.6) and sh.top > Inches(4.8)]
    smalls.sort(key=lambda x: x.left)
    for sh, v in zip(smalls, ["答辩人：XXX", "指导老师：XXX"]):
        sh.text_frame.text = ""
        p = sh.text_frame.paragraphs[0]
        r = p.add_run()
        r.text = v
        _fmt_run(r, 13, False, WHITE)
    return s


def slide_toc(prs, layout, items):
    s = prs.slides.add_slide(layout)
    # 占位符：每项有 英文(大) + 中文(小)？模板里 文本占位符85 在上、52 在下
    phs = [sh for sh in s.placeholders]
    # 按 top 排序后两两分组
    phs.sort(key=lambda x: (x.top, x.left))
    # 模板：先出现中文标题（y≈1.79），再出现英文（y≈2.35）
    pairs = []
    for sh in phs:
        pairs.append(sh)
    # 按 y 分组：每个条目由「中文（上）」和「英文（下）」组成
    groups = {}
    for sh in phs:
        key = round(Emu(sh.top).inches, 1)
        groups.setdefault(key, []).append(sh)
    ys = sorted(groups.keys())
    # 合并相邻的两行成一组（中文行 y 更小）
    entries = []
    i = 0
    while i < len(ys):
        cn_y = ys[i]
        en_y = ys[i + 1] if i + 1 < len(ys) else None
        entries.append((cn_y, en_y))
        i += 2
    for (number, cn_text, en_text), (cn_y, en_y) in zip(items, entries):
        for sh in groups[cn_y]:
            sh.text_frame.text = ""
            p = sh.text_frame.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT
            r = p.add_run()
            # 模板版式里已经有 01/02/03/04，这里只写中文标题
            r.text = cn_text
            _fmt_run(r, 20, True, NAVY)
        if en_y is not None:
            for sh in groups[en_y]:
                sh.text_frame.text = ""
                p = sh.text_frame.paragraphs[0]
                p.alignment = PP_ALIGN.LEFT
                r = p.add_run()
                r.text = en_text
                _fmt_run(r, 10, False, GRAY)
    return s


def slide_section(prs, layout, cn_title, en_title):
    s = prs.slides.add_slide(layout)
    s.shapes.title.text = ""
    p = s.shapes.title.text_frame.paragraphs[0]
    p.alignment = PP_ALIGN.LEFT
    r = p.add_run()
    r.text = cn_title
    _fmt_run(r, 36, True, NAVY)
    # 英文副标题占位符
    for sh in s.placeholders:
        if sh is s.shapes.title:
            continue
        if sh.has_text_frame and sh.top and Emu(sh.top).inches > 4.5:
            sh.text_frame.text = ""
            p = sh.text_frame.paragraphs[0]
            p.alignment = PP_ALIGN.LEFT
            r = p.add_run()
            r.text = en_title
            _fmt_run(r, 12, False, GRAY)
    return s


def slide_content(prs, layout, ttl, sub=None):
    s = prs.slides.add_slide(layout)
    title(s, ttl, sub)
    return s


def slide_ending(prs, layout):
    from pptx.enum.shapes import PP_PLACEHOLDER
    s = prs.slides.add_slide(layout)
    for sh in s.placeholders:
        ptype = sh.placeholder_format.type
        if ptype == PP_PLACEHOLDER.PICTURE:
            try:
                pic = sh.insert_picture(campus_photo())
                # 图比框"窄"，上下各裁掉约 14% 才能填满整幅横条
                img_ar = 2889 / 1129
                frame_ar = (13.33 / 3.75)
                crop = (1 - img_ar / frame_ar) / 2
                pic.crop_top = crop
                pic.crop_bottom = crop
            except Exception as e:
                print("结尾页配图跳过:", e)
        elif ptype == PP_PLACEHOLDER.TITLE:
            sh.text_frame.text = ""
            p = sh.text_frame.paragraphs[0]
            p.alignment = PP_ALIGN.CENTER
            r = p.add_run()
            r.text = "恳请批评指正"
            _fmt_run(r, 40, True, NAVY)

    # 底部的两个小占位符按左→右依次是「答辩人」「指导老师」
    bottoms = [sh for sh in s.placeholders if sh.has_text_frame
               and sh.top and sh.top > Inches(6.0)]
    bottoms.sort(key=lambda x: x.left)
    for sh, txt in zip(bottoms, ["答辩人：XXX", "指导老师：XXX"]):
        sh.text_frame.text = ""
        p = sh.text_frame.paragraphs[0]
        p.alignment = PP_ALIGN.CENTER
        r = p.add_run()
        r.text = txt
        _fmt_run(r, 14, False, NAVY)
    return s


# ---------------------------------------------------------------- 主流程

def main():
    prs = Presentation(TEMPLATE)
    clear_slides(prs)
    L_cover = prs.slide_masters[0].slide_layouts[0]
    L_toc = prs.slide_masters[0].slide_layouts[3]
    L_sec = [prs.slide_masters[0].slide_layouts[i] for i in range(6, 10)]
    L_body = prs.slide_masters[0].slide_layouts[12]
    L_end = prs.slide_masters[0].slide_layouts[15]

    # 1 封面
    slide_cover(prs, L_cover)

    # 2 目录
    slide_toc(prs, L_toc, [
        ("01", "选题与目标", "BACKGROUND  &  OBJECTIVES"),
        ("02", "架构与模型部署", "ARCHITECTURE  &  MODEL  DEPLOYMENT"),
        ("03", "操作系统机制", "PROCESS  ·  IPC  ·  SCHEDULING"),
        ("04", "实验与总结", "EXPERIMENTS  &  SUMMARY"),
    ])

    # ============ 01 选题与目标 ============
    slide_section(prs, L_sec[0], "选题与目标", "BACKGROUND  &  OBJECTIVES")

    s = slide_content(prs, L_body, "选题背景与实验目标",
                      "端侧多模型流水线：难的不是模型能跑，而是让它按时、可观测地跑完")
    # 三栏
    cols = [
        ("为什么做", NAVY, [
            "人脸识别是门禁、支付、无网场景的刚需，必须端侧离线完成",
            "一条流水线要串三个模型：检测 → 活体 → 识别",
            "真实瓶颈常常不是模型精度，而是资源分配与调度",
        ]),
        ("做了什么", BLUE, [
            "SCRFD 检测（框 + 5 关键点）",
            "MiniFASNet 双模型活体（防照片/屏幕翻拍）",
            "MobileFaceNet 识别（512 维特征 + 余弦匹配）",
        ]),
        ("操作系统视角", NAVY, [
            "推理拆到独立进程，Binder + 共享内存传帧",
            "线程池 + 信号量做有界并发与背压",
            "埋点 + Trace 定位瓶颈，再做优化对照",
        ]),
    ]
    x0, gap, cw = 0.62, 0.28, 3.92
    for i, (head, color, items) in enumerate(cols):
        x = x0 + i * (cw + gap)
        rect(s, x, 1.40, cw, 0.42, fill=color)
        tb(s, x + 0.12, 1.46, cw - 0.2, 0.3, head, size=13.5, color=WHITE, bold=True)
        rect(s, x, 1.82, cw, 2.78, fill=LIGHT2, line=RGBColor(0xE2, 0xE7, 0xF4))
        box = s.shapes.add_textbox(Inches(x + 0.14), Inches(1.94), Inches(cw - 0.28), Inches(2.55))
        tf = box.text_frame
        tf.word_wrap = True
        tf.margin_left = 0
        tf.margin_top = 0
        for j, t in enumerate(items):
            p = tf.paragraphs[0] if j == 0 else tf.add_paragraph()
            p.line_spacing = 1.42
            p.space_after = Pt(10)
            r0 = p.add_run()
            r0.text = "· "
            _fmt_run(r0, 12, True, color)
            r1 = p.add_run()
            r1.text = t
            _fmt_run(r1, 11.5, False, TEXT)

    # 验收任务横条
    rect(s, 0.62, 4.78, 12.16, 0.46, fill=NAVY)
    tb(s, 0.75, 4.84, 11.9, 0.32,
       "验收任务：实时检测框与 5 关键点 · 身份注册与匹配 · 真人/翻拍判定 · 系统状态页（PID / TID / 队列 / 耗时）",
       size=12.5, color=WHITE, bold=True)
    tb(s, 0.62, 5.42, 12.1, 1.2, [
        ("四项可验证指标", 12.5, True, NAVY),
        ("① 三个模型全部在设备上推理，无云端调用    ② 推理进程可被独立杀死并自动重连    "
         "③ 并发超过许可数时出现可观测的丢弃    ④ 优化前后有同条件对照数据", 11.5, False, TEXT),
    ], size=11.5, line_spacing=1.6)

    # ============ 02 架构与模型部署 ============
    slide_section(prs, L_sec[1], "架构与模型部署", "ARCHITECTURE  &  MODEL  DEPLOYMENT")

    s = slide_content(prs, L_body, "系统架构：两个进程、六类线程",
                      "主进程只做采集与界面，三个模型与特征库全部落在 :inference")

    # 左：主进程
    rect(s, 0.6, 1.42, 5.55, 4.35, fill=WHITE, line=NAVY, line_w=1.5)
    rect(s, 0.6, 1.42, 5.55, 0.42, fill=NAVY)
    tb(s, 0.72, 1.47, 5.3, 0.3, "主进程  com.example.androidfacedetector", size=12, color=WHITE, bold=True)
    left_rows = [
        ("main", "Compose 界面 · 结果叠加 · 状态刷新", "UI 线程"),
        ("CameraX-core_camera_0/1", "相机采集 · 预览 Surface", "提供帧回调"),
        ("cam-analysis-0/1/2", "YUV→Bitmap · 写共享内存 · 发起 Binder 调用", "线程池 3"),
    ]
    yy = 1.98
    for name, desc, tag in left_rows:
        rect(s, 0.78, yy, 5.2, 1.05, fill=LIGHT2)
        tb(s, 0.9, yy + 0.08, 3.9, 0.30, name, size=11.5, color=NAVY, bold=True, font=MONO)
        tb(s, 4.5, yy + 0.10, 1.35, 0.26, tag, size=9.5, color=MUTED, align=PP_ALIGN.RIGHT)
        tb(s, 0.9, yy + 0.44, 4.95, 0.55, desc, size=10.5, color=TEXT, line_spacing=1.2)
        yy += 1.17
    tb(s, 0.8, 5.32, 5.2, 0.35, "持有：SharedMemory 缓冲池 · IPC 时延统计 · 帧级事件记录",
       size=10, color=MUTED)

    # 右：推理进程
    rect(s, 7.15, 1.42, 5.6, 4.35, fill=WHITE, line=BLUE, line_w=1.5)
    rect(s, 7.15, 1.42, 5.6, 0.42, fill=BLUE)
    tb(s, 7.27, 1.47, 5.3, 0.3, ":inference  推理进程", size=12, color=WHITE, bold=True)
    right_rows = [
        ("binder:12345_1..N", "收请求 · 取信号量许可 · mapReadOnly 读帧", "Binder 线程池"),
        ("infer-0 / infer-1", "检测 → 活体 → 对齐 → 特征 → 匹配", "可配 1/2/4"),
    ]
    yy = 1.98
    for name, desc, tag in right_rows:
        rect(s, 7.33, yy, 5.25, 1.05, fill=LIGHT2)
        tb(s, 7.45, yy + 0.08, 3.9, 0.30, name, size=11.5, color=BLUE, bold=True, font=MONO)
        tb(s, 11.05, yy + 0.10, 1.4, 0.26, tag, size=9.5, color=MUTED, align=PP_ALIGN.RIGHT)
        tb(s, 7.45, yy + 0.44, 5.0, 0.55, desc, size=10.5, color=TEXT, line_spacing=1.2)
        yy += 1.17
    # 模型清单
    rect(s, 7.33, 4.36, 5.25, 1.0, fill=LIGHT)
    tb(s, 7.45, 4.44, 5.0, 0.85, [
        ("持有：4 个 ONNX Session（检测 1 · 活体 2 · 识别 1）", 10.5, True, NAVY),
        ("特征库 files/faces.bin · Semaphore · 丢弃/超时计数", 10, False, TEXT),
    ], size=10.5, line_spacing=1.35)
    tb(s, 7.35, 5.42, 5.2, 0.35, "故障隔离：推理进程崩溃时界面只显示「未连接」，1.6s 内自动重连",
       size=10, color=MUTED)

    # 中间箭头
    arrow(s, 6.18, 2.55, 7.10, 2.55, color=NAVY, width=2)
    arrow(s, 7.10, 3.62, 6.18, 3.62, color=BLUE, width=2)
    tb(s, 6.02, 2.10, 1.3, 0.42, "Binder\n只传 1 个 fd", size=9.5, color=NAVY, align=PP_ALIGN.CENTER, line_spacing=1.1)
    tb(s, 6.02, 3.70, 1.3, 0.42, "结果\nParcelable", size=9.5, color=BLUE, align=PP_ALIGN.CENTER, line_spacing=1.1)

    # 底部结论
    rect(s, 0.6, 5.95, 12.15, 0.72, fill=LIGHT)
    tb(s, 0.78, 6.02, 11.9, 0.6,
       [("为什么这样切：", 11.5, True, NAVY),
        ("推理是 CPU 密集型长任务，放在 UI 进程会抢走主线程时间片；拆进程后两个进程各自被 CPU 独立调度，"
         "任何一方崩溃都不影响另一方。", 11, False, TEXT)],
       size=11, line_spacing=1.3)

    # ---- 流水线
    s = slide_content(prs, L_body, "三模型流水线：串行五段，失败即短路",
                      "一帧数据从摄像头到屏幕身份标签，中间经过五个不可省的计算阶段")
    stages = [
        ("① 检测", "SCRFD-500M-KPS", "框 + 5 关键点", NAVY),
        ("② 活体", "MiniFASNet V2 + V1SE", "真 / 假 概率", BLUE),
        ("③ 对齐", "5 点相似变换", "112×112 正脸", NAVY),
        ("④ 特征", "MobileFaceNet", "512 维向量", BLUE),
        ("⑤ 匹配", "余弦相似度", "姓名 + 分数", NAVY),
    ]
    bw, bgap = 2.14, 0.29
    x = 0.62
    for i, (name, model, out, color) in enumerate(stages):
        rect(s, x, 1.62, bw, 0.52, fill=color)
        tb(s, x, 1.68, bw, 0.4, name, size=14, color=WHITE, bold=True, align=PP_ALIGN.CENTER)
        rect(s, x, 2.14, bw, 1.32, fill=LIGHT2, line=RGBColor(0xDF, 0xE4, 0xF2))
        tb(s, x + 0.1, 2.24, bw - 0.2, 0.5, model, size=10.5, color=NAVY, bold=True,
           align=PP_ALIGN.CENTER, line_spacing=1.15)
        tb(s, x + 0.1, 2.82, bw - 0.2, 0.45, "→ " + out, size=10, color=MUTED, align=PP_ALIGN.CENTER)
        if i < len(stages) - 1:
            arrow(s, x + bw + 0.02, 2.80, x + bw + bgap - 0.02, 2.80, color=GRAY, width=2)
        x += bw + bgap

    # 短路分支
    rect(s, 2.95, 3.62, 2.14, 0.62, fill=RGBColor(0xFB, 0xEE, 0xEE), line=RGBColor(0xD9, 0x9B, 0x9B))
    tb(s, 3.02, 3.68, 2.0, 0.5, "活体判为翻拍 → 直接返回\n不进入对齐与识别", size=10,
       color=RED, align=PP_ALIGN.CENTER, line_spacing=1.15)
    arrow(s, 4.02, 3.60, 4.02, 3.48, color=RGBColor(0xD9, 0x9B, 0x9B), width=1.5)

    # 说明
    rect(s, 0.62, 4.48, 5.95, 2.2, fill=WHITE, line=RGBColor(0xDD, 0xE3, 0xF0))
    tb(s, 0.78, 4.58, 5.6, 0.32, "为什么活体放在识别之前", size=12, color=NAVY, bold=True)
    tb(s, 0.78, 4.95, 5.65, 1.65, [
        "照片/屏幕翻拍同样能被检测和识别「认出来」，先做活体可以避免把攻击样本送进识别环节，"
        "也让「翻拍不出口令」成为一条硬约束，而不是靠界面提示。",
        ("代价可控：活体只吃 80×80 的裁剪块，比对齐 + 特征便宜得多，先拦一次的代价很小。", 10, False, MUTED),
    ], size=11, color=TEXT, line_spacing=1.45)

    rect(s, 6.78, 4.48, 5.97, 2.2, fill=LIGHT)
    tb(s, 6.94, 4.58, 5.6, 0.32, "三段可独立计时的边界", size=12, color=NAVY, bold=True)
    tb(s, 6.94, 4.95, 5.7, 1.65, [
        ("检测 pre / infer / post", 11, True, NAVY),
        ("活体推理 · 对齐 · 特征提取", 11, True, NAVY),
        ("对齐与特征分别计时，避免把 112×112 重采样混进推理耗时", 10.5, False, TEXT),
        ("每段都通过 Binder 回传，界面与状态页共用同一份数据", 10.5, False, TEXT),
    ], size=11, line_spacing=1.5)

    # ---- 模型部署细节
    s = slide_content(prs, L_body, "三个模型的端侧部署细节",
                      "输入尺寸全部在导出时固定，运行期不做动态 shape；预处理差异是最大的坑")
    data = [
        ["模型", "体积", "输入", "输出", "预处理 / 解码要点"],
        ["SCRFD-500M-KPS\n(检测)", "2.52 MB", "640×640\n(可切 320)",
         "9 张量\n12800/3200/800\n× (1·4·10)",
         "letterbox 等比缩放；(x-127.5)/128；\n锚点距离解码 + IoU 0.4 NMS"],
        ["MiniFASNetV2\n+ V1SE\n(活体)", "1.74 MB\n×2", "80×80",
         "3 类概率\n双模型逐类累加",
         "BGR 顺序、0~255 不归一化；\n按 scale 2.7/4.0 以框心外扩裁剪；\ncv2 语义双线性缩放"],
        ["MobileFaceNet\n(识别)", "13.62 MB", "112×112", "512 维特征",
         "5 点相似变换对齐；L2 归一化；\n余弦相似度，阈值 0.5"],
    ]
    table(s, 0.62, 1.48, 12.1, 3.55, data,
          col_ratio=[2.0, 1.05, 1.25, 1.85, 4.4], font_size=10.5, header_size=11.5,
          row_h=[0.42, 1.05, 1.25, 0.85])

    # 底部两个提示
    rect(s, 0.62, 5.28, 5.95, 1.35, fill=LIGHT)
    tb(s, 0.78, 5.36, 5.6, 1.2, [
        ("活体模型的输入是 0~255，不是 0~1", 11.5, True, NAVY),
        ("官方 to_tensor 被改过（源码注释 modify by zkx），按常识归一化会让三张官方样例全判错。", 10.5, False, TEXT),
    ], size=11, line_spacing=1.35)
    rect(s, 6.78, 5.28, 5.97, 1.35, fill=LIGHT)
    tb(s, 6.94, 5.36, 5.65, 1.2, [
        ("模型随仓库分发，共 19.6 MB", 11.5, True, NAVY),
        ("来源、许可、转换脚本见 MODELS.md；clone 后无需另行下载即可运行。", 10.5, False, TEXT),
    ], size=11, line_spacing=1.35)

    # ============ 03 操作系统机制 ============
    slide_section(prs, L_sec[2], "操作系统机制", "PROCESS  ·  IPC  ·  SCHEDULING")

    s = slide_content(prs, L_body, "进程管理与 Binder IPC",
                      "一帧 1.2 MB 的像素不进 Binder 事务体，只传一个文件描述符")
    code_box(s, 0.62, 1.42, 6.05, 2.05, [
        "interface IFaceInference {",
        "  FaceResultParcel analyze(",
        "        in SharedMemory frame,",
        "        int width, int height,",
        "        long requestId, long captureNs);",
        "  void configure(int threads, int permits);",
        "  ServiceStatsParcel getStats();",
        "}",
    ], size=10.5)

    tb(s, 0.62, 3.66, 6.05, 3.0, [
        ("三个关键决策", 12, True, NAVY),
        ("① 传帧走共享内存", 11.5, True, BLUE),
        ("640×480 ARGB = 1.2 MB，超过 Binder 单次事务 1 MB 上限，直接塞进事务体会抛 "
         "TransactionTooLargeException", 10.5, False, TEXT),
        ("② 服务端 mmap 只读", 11.5, True, BLUE),
        ("主进程写 ashmem，Parcel 里只带 1 个 fd；服务端 mapReadOnly() 读同一块物理页，"
         "像素只写一次、读一次", 10.5, False, TEXT),
        ("③ 同步返回，超时可退", 11.5, True, BLUE),
        ("Binder 线程提交线程池后 future.get(3s)，超时计数并返回，不永久挂住 Binder 线程",
         10.5, False, TEXT),
    ], size=11, line_spacing=1.35, space_after=4)

    # 右侧：数据流
    rect(s, 6.95, 1.48, 5.8, 2.1, fill=LIGHT2, line=RGBColor(0xDD, 0xE3, 0xF0))
    steps = [
        ("帧转换", "cam-analysis-0"),
        ("写 ashmem", "1.2 MB"), ("Binder", "1 个 fd"), ("读帧", "infer-0"),
    ]
    bx = 7.12
    for i, (n, d) in enumerate(steps):
        rect(s, bx, 1.62, 1.22, 0.86, fill=WHITE, line=BLUE)
        tb(s, bx, 1.68, 1.22, 0.3, n, size=10.5, color=NAVY, bold=True, align=PP_ALIGN.CENTER)
        tb(s, bx, 2.0, 1.22, 0.4, d, size=8.5, color=MUTED, align=PP_ALIGN.CENTER)
        if i < 3:
            arrow(s, bx + 1.24, 2.05, bx + 1.44, 2.05, color=GRAY, width=1.5)
        bx += 1.42
    tb(s, 7.12, 2.62, 5.4, 0.85, [
        ("跨进程前后像素只被搬运一次：", 10.5, True, NAVY),
        ("写 ashmem → 读映射页，中间没有 Binder 内核缓冲区拷贝。", 10.5, False, TEXT),
    ], size=10.5, line_spacing=1.3)

    # 实测数字条
    rect(s, 6.95, 3.82, 5.8, 1.5, fill=NAVY)
    tb(s, 7.15, 3.95, 5.4, 0.4, "实测（模拟器 · 43 帧均值）", size=10.5, color=RGBColor(0xC9, 0xCF, 0xE8))
    tb(s, 7.15, 4.28, 5.45, 0.95, [
        ("IPC 往返 31.83 ms   ≈   服务端推理 31.75 ms", 14, True, WHITE),
        ("⇒ IPC 净开销 0.03 ms，跨进程本身几乎不花钱", 12, False, RGBColor(0xD5, 0xDA, 0xEE)),
    ], size=12, line_spacing=1.45)

    tb(s, 6.95, 5.48, 5.8, 1.2, [
        ("崩溃重连：", 11, True, NAVY),
        ("debug 页「崩溃服务」→ binderDied 回调 → 重新 bindService，"
         "实测 1.6 s 恢复到新进程，期间界面只显示「未连接」。", 10.5, False, TEXT),
    ], size=11, line_spacing=1.4)

    # ---- 单双进程对比
    s = slide_content(prs, L_body, "单进程 vs 双进程：同一引擎，只差跨不跨进程",
                      "两种模式共用 InferenceEngine，唯一变量是「是否跨进程」，保证对照公平")
    data = [
        ["指标", "双进程（DUAL）", "单进程（SINGLE）", "说明"],
        ["端到端平均", "95.43 ms", "106.43 ms", "双进程快约 10%"],
        ["P50", "87.28 ms", "103.75 ms", "中位延迟同样占优"],
        ["P95", "142.87 ms", "130.13 ms", "单进程尾部更稳"],
        ["样本数", "264 帧", "236 帧", "各采样 25 s"],
        ["故障隔离", "有（可独立杀死）", "无", "UI 与推理同生共死"],
        ["可观测性", "两进程 PID / TID 分离", "只有一组线程", "状态页数据来源不同"],
    ]
    table(s, 0.62, 1.48, 7.55, 3.6, data, col_ratio=[1.5, 2.0, 2.0, 2.4],
          font_size=11, header_size=11, row_h=0.44)

    rect(s, 8.42, 1.48, 4.34, 3.6, fill=LIGHT)
    tb(s, 8.6, 1.6, 4.0, 3.35, [
        ("怎么解读", 12.5, True, NAVY),
        ("双进程平均更快：", 11, True, BLUE),
        ("推理不占 UI 进程的 CPU 时间片，帧转换与叠加绘制不被长任务阻塞。", 10.5, False, TEXT),
        ("但尾部更抖：", 11, True, BLUE),
        ("跨进程多一层调度与唤醒，偶发则拉高 P95。", 10.5, False, TEXT),
        ("所以选双进程的理由不是平均延迟，而是隔离与可观测性——"
         "推理崩了界面还能提示，两个进程的线程栈也能分别看。", 10.5, True, NAVY),
    ], size=11, line_spacing=1.4, space_after=5)

    tb(s, 0.62, 5.28, 12.1, 1.3, [
        ("对照实验怎么保证公平", 12, True, NAVY),
        ("两种模式都调用同一个 InferenceEngine（同样的模型、同样的预处理、同样的特征库），"
         "切换只发生在 EngineRouter 的路由层；实测时固定检测分辨率 640、不跳帧、同样 25 秒采样，"
         "仅改变「是否经过 Binder」。", 11, False, TEXT),
    ], size=11, line_spacing=1.45)

    # ---- 线程与信号量
    s = slide_content(prs, L_body, "线程调度与信号量：三层并发控制",
                      "从相机回调到推理线程，每一层都限制在途任务数，最外层用信号量做背压")
    layers = [
        ("① CameraX 回调", "cam-analysis-0/1/2", "Analyzer 串行回调，天然只有 1 帧在途；解码后立即 close()，不 close 就不再送新帧", NAVY),
        ("② Binder 服务端", "binder:12345_1..N", "tryAcquire(60 ms) 拿不到许可立即返回 DROPPED，把「处理不过来」第一时间告诉调用方", BLUE),
        ("③ 推理线程池", "infer-0/1（可配 1/2/4）", "ThreadPoolExecutor 固定线程数，任务超时 3 s 记 TIMEOUT，异常记 ERROR", NAVY),
    ]
    yy = 1.46
    for name, sub, desc, color in layers:
        rect(s, 0.62, yy, 6.15, 1.35, fill=WHITE, line=color, line_w=1.25)
        rect(s, 0.62, yy, 0.09, 1.35, fill=color)
        tb(s, 0.82, yy + 0.10, 5.8, 0.3, name, size=12, color=NAVY, bold=True)
        tb(s, 3.0, yy + 0.12, 3.6, 0.26, sub, size=9.5, color=MUTED, align=PP_ALIGN.RIGHT, font=MONO)
        tb(s, 0.82, yy + 0.46, 5.8, 0.8, desc, size=10.5, color=TEXT, line_spacing=1.3)
        yy += 1.46

    code_box(s, 7.02, 1.46, 5.72, 2.42, [
        "if (!permits.tryAcquire(60, MILLISECONDS)) {",
        "    dropped.incrementAndGet()",
        "    return FaceResultParcel(STATUS_DROPPED)",
        "}",
        "inFlight.incrementAndGet()",
        "try {",
        "    val task = pool.submit { engine.analyze(...) }",
        "    return task.get(3, TimeUnit.SECONDS)",
        "} finally {",
        "    inFlight.decrementAndGet()",
        "    permits.release()      // 正常/超时/异常都归还",
        "}",
    ], size=10)

    rect(s, 7.02, 4.02, 5.72, 1.32, fill=NAVY)
    tb(s, 7.2, 4.12, 5.4, 0.32, "压测：同时提交 8 帧", size=10.5, color=RGBColor(0xC9, 0xCF, 0xE8))
    tb(s, 7.2, 4.42, 5.4, 0.85, [
        ("许可 1 → 完成 3 / 丢弃 5（136 ms）", 11.5, True, WHITE),
        ("许可 4 → 完成 4 / 丢弃 4（146 ms）", 11.5, True, WHITE),
    ], size=11.5, line_spacing=1.4)
    tb(s, 7.02, 5.44, 5.72, 1.15, [
        ("限流买的是「快速失败」，不是更高吞吐：", 10.5, True, NAVY),
        ("丢弃让积压不进入队列，延迟可控；调大许可虽接纳更多，但 CPU 争抢使单帧变慢。", 10.5, False, TEXT),
    ], size=10.5, line_spacing=1.35)

    # ---- 状态页
    s = slide_content(prs, L_body, "系统状态页：把进程与线程状态搬到界面上",
                      "覆盖层实现，相机不停帧，数据才是实时的")
    img_placeholder(s, 0.62, 1.46, 6.55, 4.75,
                    "系统状态页截图", "含两进程 PID、线程列表、许可、队列、丢弃、耗时")
    tb(s, 7.42, 1.46, 5.3, 4.9, [
        ("页面上能看到什么", 12.5, True, NAVY),
        ("进程与 IPC", 11.5, True, BLUE),
        ("UI 进程与 :inference 进程 PID、线程列表（含 TID）、IPC 调用次数与 avg/max 时延", 10.5, False, TEXT),
        ("同步与队列", 11.5, True, BLUE),
        ("可用许可 / 总许可、在途任务数、队列长度、已处理 / 丢弃 / 超时 / 异常", 10.5, False, TEXT),
        ("耗时分布", 11.5, True, BLUE),
        ("各阶段平均耗时与 P50 / P95，单帧服务端耗时", 10.5, False, TEXT),
        ("在线改配置", 11.5, True, BLUE),
        ("单/双进程、线程数 1/2/4、许可 1/2/4、检测分辨率 640/320、跳帧 1/2/4", 10.5, False, TEXT),
    ], size=11, line_spacing=1.4, space_after=4)

    rect(s, 0.62, 6.28, 12.1, 0.52, fill=LIGHT)
    tb(s, 0.78, 6.36, 11.9, 0.4,
       "踩坑：最初做成独立 Activity，一进去 MainActivity 就进入 stop、CameraX 停帧，队列与信号量数据立刻归零 —— 改成覆盖层才有观测意义。",
       size=10.5, color=MUTED)

    # ============ 04 实验与总结 ============
    slide_section(prs, L_sec[3], "实验与总结", "EXPERIMENTS  &  SUMMARY")

    s = slide_content(prs, L_body, "摄像头 I/O 事件链与埋点",
                      "普通应用写不了中断服务程序，能分析的是「中断驱动 I/O 在应用层的可观测路径」")
    chain = [
        ("传感器 / CIS", "硬件，产生帧", "不可见"),
        ("ISP + 驱动 /dev/video*", "V4L2 出帧，内核中断驱动", "不可见"),
        ("Camera HAL / cameraserver", "系统服务，CameraX 通过 Binder 调用", "调用可见"),
        ("CameraX ImageAnalysis", "回调落在 cam-analysis-N 线程", "可见"),
        ("共享内存 + Binder", "写 ashmem，服务端 mapReadOnly", "可见"),
        (":inference 推理", "infer-0/1 检测 → 活体 → 对齐 → 特征", "可见"),
    ]
    yy = 1.44
    for i, (name, desc, vis) in enumerate(chain):
        color = GRAY if vis == "不可见" else NAVY
        rect(s, 0.62, yy, 7.55, 0.72, fill=WHITE if vis == "不可见" else LIGHT2,
             line=RGBColor(0xDD, 0xE3, 0xF0))
        rect(s, 0.62, yy, 0.07, 0.72, fill=color)
        tb(s, 0.78, yy + 0.06, 4.4, 0.3, name, size=10.5, color=color, bold=True, font=MONO)
        tb(s, 0.78, yy + 0.38, 5.3, 0.28, desc, size=9.5, color=TEXT)
        tb(s, 6.4, yy + 0.22, 1.6, 0.3, vis, size=9, color=MUTED, align=PP_ALIGN.RIGHT)
        yy += 0.84

    rect(s, 8.45, 1.44, 4.3, 2.55, fill=LIGHT)
    tb(s, 8.62, 1.54, 4.0, 0.32, "自定义 atrace 埋点", size=11.5, color=NAVY, bold=True)
    tb(s, 8.62, 1.90, 4.0, 2.0, [
        ("frame.convert", 10, False, TEXT),
        ("ipc.analyze / ipc.register", 10, False, TEXT),
        ("infer.detect / spoof / align / embed / match", 10, False, TEXT),
        ("svc.readFrame / svc.infer", 10, False, TEXT),
        ("frame.dropped", 10, False, TEXT),
    ], size=10, line_spacing=1.6, font=MONO)

    rect(s, 8.45, 4.12, 4.3, 2.15, fill=WHITE, line=RGBColor(0xDD, 0xE3, 0xF0))
    tb(s, 8.62, 4.22, 4.0, 0.32, "与硬件中断的区别", size=11.5, color=NAVY, bold=True)
    tb(s, 8.62, 4.58, 3.98, 1.6, [
        "中断由 CIS/ISP 触发、内核处理；应用层只能看到「帧什么时候到回调」，"
        "以及自己在每段上打的点。Trace 里的 sched / binder_driver 事件用来补足调度与 IPC 侧的信息。",
    ], size=10.5, color=TEXT, line_spacing=1.35)

    # ---- Trace 分析
    s = slide_content(prs, L_body, "系统 Trace 分析：瓶颈不在 IPC",
                      "atrace 抓 5 秒，44 帧；应用自定义区间全部被系统捕获")
    data = [
        ["区间", "次数", "平均 ms", "P50", "P95", "最大"],
        ["frame.convert", "43", "80.82", "76.96", "90.80", "198.95"],
        ["infer.detect", "43", "31.75", "28.60", "49.45", "76.47"],
        ["ipc.analyze", "43", "31.83", "28.68", "49.59", "76.60"],
    ]
    table(s, 0.62, 1.46, 5.95, 1.9, data, col_ratio=[2.2, 0.8, 1.1, 1.0, 1.0, 1.0],
          font_size=11, header_size=10.5, row_h=0.42)

    code_box(s, 0.62, 3.52, 5.95, 1.72, [
        "+  0.00 ms   frame.convert 开始",
        "+ 80.31 ms   frame.convert 结束   ← 瓶颈",
        "+ 83.59 ms   ipc.analyze 开始",
        "+ 83.63 ms     infer.detect 开始   ← 跨进程后",
        "+112.07 ms     infer.detect 结束",
        "+112.10 ms   ipc.analyze 结束      ← 净开销 0.03",
    ], size=10)
    tb(s, 0.62, 5.36, 5.95, 1.3, [
        ("抓取与分析", 11, True, NAVY),
        ("adb shell atrace -t 5 -a com.example.androidfacedetector \\", 9, False, TEXT),
        ("        sched view gfx binder_driver -b 8192 > trace.txt", 9, False, TEXT),
        ("python tools/analyze_trace.py trace.txt   # 按区间统计 avg / P50 / P95 并还原单帧时间线",
         9, False, MUTED),
    ], size=11, line_spacing=1.5, space_after=3)

    rect(s, 6.85, 1.46, 5.9, 1.82, fill=NAVY)
    tb(s, 7.05, 1.58, 5.5, 0.32, "关键发现", size=11, color=RGBColor(0xC9, 0xCF, 0xE8))
    tb(s, 7.05, 1.92, 5.5, 1.5, [
        ("IPC 往返 ≈ 服务端耗时", 15, True, WHITE),
        ("跨进程本身只花 0.03 ms；真正吃掉一半时间的是 YUV→Bitmap 的帧转换（80 ms）。",
         11, False, RGBColor(0xD5, 0xDA, 0xEE)),
    ], size=11, line_spacing=1.45)

    rect(s, 6.85, 3.44, 5.9, 2.66, fill=LIGHT)
    tb(s, 7.02, 3.56, 5.55, 0.32, "这解释了两件事", size=11.5, color=NAVY, bold=True)
    tb(s, 7.02, 3.94, 5.55, 2.0, [
        ("① 共享内存是对的但不够：", 10.5, True, BLUE),
        ("它把 IPC 开销压到可忽略，可总延迟仍取决于转换环节，单看总延迟会误判瓶颈。", 10.5, False, TEXT),
        ("② 优化方向被重新排序：", 10.5, True, BLUE),
        ("优先降转换成本与推理频率，而不是继续优化 Binder 传输。", 10.5, False, TEXT),
    ], size=10.5, line_spacing=1.4, space_after=4)

    # ---- 优化对照
    s = slide_content(prs, L_body, "优化对照实验：模型侧与系统侧各一项",
                      "模拟器 x86_64 · 每组采样 25 s · 由 App 内 MetricsRecorder 逐帧记录后统计")
    # 柱状图
    base_y, max_h = 5.42, 3.35
    bars = [
        ("基线\nDUAL/640/skip1", 95.43, NAVY),
        ("模型侧\nDUAL/320/skip1", 65.54, BLUE),
        ("系统侧\nDUAL/640/skip2", 76.30, RGBColor(0x7E, 0x8F, 0xD9)),
        ("进程对照\nSINGLE/640/skip1", 106.43, GRAY),
    ]
    bx0, bw2, bgap2 = 0.95, 1.18, 0.34
    # 轴
    arrow(s, 0.62, base_y, 0.62, 1.55, color=RGBColor(0xC5, 0xCB, 0xDA), width=1)
    for v in (0, 30, 60, 90, 120):
        y = base_y - max_h * v / 120.0
        arrow(s, 0.62, y, 6.78, y, color=RGBColor(0xE6, 0xEA, 0xF3), width=0.75)
        tb(s, 0.05, y - 0.12, 0.55, 0.24, str(v), size=8, color=MUTED, align=PP_ALIGN.RIGHT)
    for i, (name, val, color) in enumerate(bars):
        x = bx0 + i * (bw2 + bgap2)
        h = max_h * val / 120.0
        rect(s, x, base_y - h, bw2, h, fill=color)
        tb(s, x, base_y - h - 0.32, bw2, 0.28, "%.1f" % val, size=11, color=NAVY, bold=True,
           align=PP_ALIGN.CENTER)
        tb(s, x - 0.2, base_y + 0.08, bw2 + 0.4, 0.6, name, size=8.5, color=TEXT,
           align=PP_ALIGN.CENTER, line_spacing=1.2)
    tb(s, 0.62, 1.28, 2.6, 0.26, "端到端平均延迟 (ms)", size=9, color=MUTED)

    # 右侧数据表
    data = [
        ["配置", "样本", "avg", "P95", "检测推理"],
        ["det640 / skip1", "264", "95.43", "142.87", "27.81"],
        ["det320 / skip1", "384", "65.54", "108.84", "8.61"],
        ["det640 / skip2", "265", "76.30", "113.41", "24.75"],
        ["SINGLE / 640", "236", "106.43", "130.13", "21.78"],
    ]
    table(s, 6.98, 1.46, 5.72, 1.8, data, col_ratio=[2.1, 0.9, 1.0, 1.0, 1.25],
          font_size=9.5, header_size=9.5, row_h=0.36)

    tb(s, 6.98, 3.42, 5.7, 2.9, [
        ("三条结论", 11.5, True, NAVY),
        ("① 分辨率 640→320：检测推理 27.81 → 8.61 ms（3.2×），端到端 -31%", 10.5, False, TEXT),
        ("代价：最小可检人脸尺寸翻倍，远处小脸会漏检", 9.5, False, MUTED),
        ("② 跳帧 2：端到端 -20%，P95 从 142.87 降到 113.41（抖动收敛 21%）", 10.5, False, TEXT),
        ("价值在稳定性：跳过的那帧连 YUV→Bitmap 一起省掉", 9.5, False, MUTED),
        ("③ 单进程：均值更差，但 P95 更低、方差更小", 10.5, False, TEXT),
    ], size=10.5, line_spacing=1.45, space_after=3)

    # ---- 故障
    s = slide_content(prs, L_body, "典型故障与失败案例",
                      "每个坑都有可复现的现象和确定的根因，也都有验证手段兜底")
    data = [
        ["现象", "根因", "处理"],
        ["活体三张官方样例全判错、分数几乎相同", "官方 to_tensor 不除 255，输入应为 0~255 且为 BGR 序", "改预处理；用官方样例做回归"],
        ["端侧活体分数与 PC 差 0.3", "createScaledBitmap 与 cv2 缩放语义不同（中心对齐/抗锯齿）", "手写 cv2 语义双线性"],
        ["分数从 0.998 掉到 0.497", "双模型融合写成了覆盖而非逐类累加", "改为累加 softmax"],
        ["ORT 加载模型报 model_path must not be empty", "torch 新版 dynamo 导出器产出 opset18 / IR10", "改用传统导出器（opset11）"],
        ["共享内存写入 mmap EPERM", "ashmem 降权为只读后无法再提权", "不降权，服务端只 mapReadOnly"],
        ["状态页数据全为 0", "独立 Activity 导致主界面 stop、相机停帧", "改为覆盖层"],
    ]
    table(s, 0.62, 1.46, 12.1, 3.95, data, col_ratio=[3.0, 3.6, 3.0],
          font_size=10.5, header_size=11, row_h=[0.42, 0.6, 0.6, 0.55, 0.6, 0.6, 0.55])

    rect(s, 0.62, 5.58, 12.1, 1.1, fill=LIGHT)
    tb(s, 0.78, 5.66, 11.85, 0.95, [
        ("排查方法比结论更重要：", 11, True, NAVY),
        ("两端各自打印同一中间结果（如裁剪块的 B/G/R 通道均值），先确认「输入一致」，"
         "再怀疑「计算逻辑」——这比在真机上反复试错快得多，也能定位到像「融合被覆盖」这种肉眼看不出来的错误。",
         10.5, False, TEXT),
    ], size=10.5, line_spacing=1.35)

    # ---- 创新与局限
    s = slide_content(prs, L_body, "创新点与局限",
                      "可复现、可对照、可观测，是本项目相对「能跑起来」的增量")
    rect(s, 0.62, 1.44, 6.2, 0.42, fill=NAVY)
    tb(s, 0.74, 1.49, 5.9, 0.3, "创新与工程贡献", size=12.5, color=WHITE, bold=True)
    rect(s, 0.62, 1.86, 6.2, 2.6, fill=LIGHT2, line=RGBColor(0xE2, 0xE7, 0xF4))
    tb(s, 0.78, 1.96, 5.9, 2.45, [
        ("① 可切换的单/双进程对照", 10.5, True, BLUE),
        ("同一引擎、同一模型，只切路由，让「跨进程的代价」可被测量而不是靠推断。", 9.8, False, TEXT),
        ("② 共享内存传帧 + 信号量背压", 10.5, True, BLUE),
        ("1.2 MB 帧不进事务体，IPC 净开销 0.03 ms；限流提供快速失败而非排队。", 9.8, False, TEXT),
        ("③ 三项端侧一致性测试", 10.5, True, BLUE),
        ("检测、对齐+特征、活体均与 PC 参考实现逐值比对，容差内一致才继续开发。", 9.8, False, TEXT),
        ("④ 事件链驱动的优化决策", 10.5, True, BLUE),
        ("先用 Trace 定位瓶颈再优化，避免「凭直觉优化 Binder」这类无效工作。", 9.8, False, TEXT),
    ], size=10.5, line_spacing=1.35, space_after=4)

    rect(s, 6.9, 1.44, 5.85, 0.42, fill=RGBColor(0x6B, 0x74, 0x8C))
    tb(s, 7.02, 1.49, 5.6, 0.3, "局限与未完成", size=12.5, color=WHITE, bold=True)
    rect(s, 6.9, 1.86, 5.85, 2.6, fill=LIGHT2, line=RGBColor(0xE2, 0xE7, 0xF4))
    tb(s, 7.04, 1.96, 5.6, 2.45, [
        ("· 性能数据来自模拟器", 10.5, True, TEXT),
        ("x86_64 + 软件渲染，不代表真机；真机需按同条件重测。", 9.8, False, MUTED),
        ("· 精度未做量化评估", 10.5, True, TEXT),
        ("缺少带标注测试集，只有阈值标定与定性结论。", 9.8, False, MUTED),
        ("· 单相机下信号量不会自然触发", 10.5, True, TEXT),
        ("CameraX 回调串行，压测数据来自人为并发。", 9.8, False, MUTED),
        ("· 帧转换瓶颈未解决", 10.5, True, TEXT),
        ("ImageProxy.toBitmap() 为纯 Java 实现，占端到端一半。", 9.8, False, MUTED),
    ], size=10.5, line_spacing=1.35, space_after=4)

    rect(s, 0.62, 4.62, 12.1, 2.0, fill=LIGHT)
    tb(s, 0.8, 4.72, 11.8, 0.32, "还可以继续做（已在设计中，未实施）", size=11.5, color=NAVY, bold=True)
    tb(s, 0.8, 5.08, 11.7, 1.45, [
        ("· 模型量化：INT8 / FP16 对照，看精度损失与延迟收益的实际比值", 10.5, False, TEXT),
        ("· 帧转换替换：YUV 直接转 RGB（RenderScript / NEON），或交给 GPU 做采样", 10.5, False, TEXT),
        ("· 动作活体：在上面的 106 点关键点基础上判断张嘴、闭眼、左右转头，做「口令式活体」",
         10.5, False, TEXT),
    ], size=10.5, line_spacing=1.45)

    # ---- 总结
    s = slide_content(prs, L_body, "总结与协作证据",
                      "三项功能上屏可用，操作系统机制有代码、有数据、有可复现的观测手段")
    nums = [
        ("3", "个模型", "全部端侧离线运行\n共 19.6 MB"),
        ("2", "个进程", "UI 与推理隔离\n崩溃可重连"),
        ("-31%", "端到端", "检测分辨率 640→320\nP95 同步下降"),
        ("14", "次提交", "按功能演进分批\n每个提交可编译"),
    ]
    x = 0.62
    for big, unit, desc in nums:
        rect(s, x, 1.44, 2.9, 1.82, fill=WHITE, line=RGBColor(0xDD, 0xE3, 0xF0))
        tb(s, x + 0.1, 1.54, 2.7, 0.62, big, size=34, color=NAVY, bold=True, align=PP_ALIGN.CENTER)
        tb(s, x + 0.1, 2.14, 2.7, 0.3, unit, size=11, color=BLUE, bold=True, align=PP_ALIGN.CENTER)
        tb(s, x + 0.1, 2.5, 2.7, 0.7, desc, size=9.5, color=MUTED, align=PP_ALIGN.CENTER, line_spacing=1.25)
        x += 3.06

    rect(s, 0.62, 3.46, 6.05, 3.2, fill=LIGHT)
    tb(s, 0.78, 3.56, 5.75, 0.32, "三条结论", size=12, color=NAVY, bold=True)
    tb(s, 0.78, 3.94, 5.75, 2.6, [
        ("① 端侧三模型流水线可行", 10.5, True, BLUE),
        ("检测 + 活体 + 识别在手机上串行跑通，翻拍会被短路拦截。", 10, False, TEXT),
        ("② IPC 不是瓶颈，转换才是", 10.5, True, BLUE),
        ("共享内存让 IPC 净开销降到 0.03 ms，帧转换占端到端一半。", 10, False, TEXT),
        ("③ 限流买的是稳定性", 10.5, True, BLUE),
        ("8 帧并发下丢弃 5 帧，把「处理不过来」变成可观测的确定行为。", 10, False, TEXT),
    ], size=10.5, line_spacing=1.4, space_after=4)

    img_placeholder(s, 6.86, 3.46, 5.9, 2.05, "Git 提交历史截图", "GitHub 仓库 / Android Studio Git Log 面板")
    rect(s, 6.86, 5.62, 5.9, 1.04, fill=WHITE, line=RGBColor(0xDD, 0xE3, 0xF0))
    tb(s, 7.0, 5.7, 5.65, 0.9, [
        ("仓库：github.com/Longly42/android-face-detector-multiprocess", 9.5, False, MUTED),
        ("成员分工：（按实际填写）", 9.5, False, MUTED),
    ], size=9.5, line_spacing=1.5)

    # 结尾
    slide_ending(prs, L_end)

    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, OUT_NAME)
    prs.save(out)
    print("已生成:", out)
    print("页数:", len(prs.slides))
    print("体积: %.1f MB" % (os.path.getsize(out) / 1e6))


if __name__ == "__main__":
    main()
