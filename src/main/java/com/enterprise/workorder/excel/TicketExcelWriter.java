package com.enterprise.workorder.excel;

import com.enterprise.workorder.dto.TicketVO;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/**
 * 把工单列表流式写成 .xlsx。
 *
 * <p><b>为什么用 SXSSF 而不是 XSSF</b>：XSSF 把整张表保留在堆内存里，
 * 行数一多就 OOM；SXSSF 只在内存里保留 {@link #ROW_ACCESS_WINDOW} 行，
 * 更早的行实时刷到磁盘临时文件，内存占用与导出总行数无关。
 * 代价是已刷走的行不能再回头改，所以写入口只允许一直往下追加。</p>
 *
 * <p><b>为什么把"写出 zip"和"销毁工作簿"分成两步</b>：
 * {@link SheetWriter#flushTo(OutputStream)} 只在最后调用一次，此时才会真正产生字节。
 * 在此之前发生任何异常（参数非法、没有权限、数据库断开），响应流都还没被碰过，
 * 全局异常处理器能把错误正常转成 JSON 返回给前端。
 * 若在创建 sheet 时就把 response 的输出流传进来，一有异常就只能返回一个
 * 半截的 zip —— 前端拿到的是一句"请求失败"，而真正的原因丢在了服务端日志里。</p>
 */
public final class TicketExcelWriter {

    /** 内存中保留的行数。超过后自动把最早的行刷到磁盘临时文件 */
    private static final int ROW_ACCESS_WINDOW = 200;

    /**
     * 单张表的行数上限 1048576（含表头）。
     *
     * <p>这不是 POI 的限制，是 .xlsx 格式本身的规格。超出后 SXSSF 不报错，
     * 而是把数据写到不存在的行号上 —— 数据静默丢失。所以必须自己拦住。</p>
     */
    public static final int MAX_ROWS_PER_SHEET = 1_048_576;

    private static final DateTimeFormatter DATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 列定义：表头 + 列宽（字符数）+ 取值函数。加列只改这一处 */
    private record Column(String header, int width, Function<TicketVO, String> getter) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("工单编号", 22, TicketVO::getTicketNo),
            new Column("标题", 40, TicketVO::getTitle),
            new Column("类型", 14, TicketVO::getTypeName),
            new Column("优先级", 10, TicketVO::getPriorityLabel),
            new Column("状态", 12, TicketVO::getStatusLabel),
            new Column("发起部门", 16, TicketVO::getDepartmentName),
            new Column("创建人", 12, TicketVO::getCreatorName),
            new Column("当前处理人", 12, TicketVO::getCurrentApproverName),
            new Column("当前进度", 10, TicketExcelWriter::formatProgress),
            new Column("提交时间", 20, v -> formatDateTime(v.getSubmittedAt())),
            new Column("办结时间", 20, v -> formatDateTime(v.getFinishedAt())),
            new Column("创建时间", 20, v -> formatDateTime(v.getCreatedAt())),
            new Column("内容", 60, TicketVO::getContent));

    private TicketExcelWriter() {
    }

    /**
     * 建表并写表头。
     *
     * @param notice 若非空，写在表头下方、数据上方，用于提示"本次结果被截断"。
     *               必须在建表时传入：SXSSF 已刷出的行无法回头插入。
     */
    public static SheetWriter createSheet(String notice) {
        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        // 关闭临时文件压缩：省磁盘但要耗 CPU，而导出的瓶颈在数据库读取与网络写出，
        // 压缩的收益低于它增加的延迟。
        workbook.setCompressTempFiles(false);

        SXSSFSheet sheet = workbook.createSheet("工单列表");
        CellStyle headerStyle = buildHeaderStyle(workbook);
        CellStyle bodyStyle = buildBodyStyle(workbook);

        Row header = sheet.createRow(0);
        for (int i = 0; i < COLUMNS.size(); i++) {
            Column column = COLUMNS.get(i);
            Cell cell = header.createCell(i);
            cell.setCellValue(column.header());
            cell.setCellStyle(headerStyle);
            // 列宽单位是 1/256 个字符宽，乘 256 是为了让上面写的"字符数"符合直觉
            sheet.setColumnWidth(i, column.width() * 256);
        }
        // 冻结首行：数据一多，滚下去就看不到表头了，只能靠数列号猜。
        sheet.createFreezePane(0, 1);

        int firstDataRow = 1;
        if (notice != null && !notice.isBlank()) {
            Cell cell = sheet.createRow(1).createCell(0);
            cell.setCellValue(notice);
            cell.setCellStyle(buildNoticeStyle(workbook));
            firstDataRow = 2;
        }

        return new SheetWriter(workbook, bodyStyle, firstDataRow);
    }

    /** 当前进度列：形如 2/3；未提交（totalStep 为空）显示 "-" */
    private static String formatProgress(TicketVO vo) {
        Integer total = vo.getTotalStep();
        if (total == null || total <= 0) {
            return "-";
        }
        int current = vo.getCurrentStep() == null ? 0 : vo.getCurrentStep();
        return current + "/" + total;
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FMT);
    }

    /**
     * 表头样式：加粗白字 + 灰底 + 居中 + 边框。
     *
     * <p><b>样式对象必须复用。</b>每个 CellStyle 占一个工作簿级样式槽，
     * .xlsx 每张表最多 64000 个。若在循环里给每个单元格 new 一个样式，
     * 超过上限后 POI 抛 "Too many cell formats"，表现为"导到某个数量就崩"。
     * 所以本类只在建表时构建三个样式，全表共用。</p>
     */
    private static CellStyle buildHeaderStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        applyBorder(style);
        return style;
    }

    private static CellStyle buildBodyStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.TOP);
        // 内容列可能很长，允许换行；行高交给 Excel 自适应
        style.setWrapText(true);
        applyBorder(style);
        return style;
    }

    private static CellStyle buildNoticeStyle(SXSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setColor(IndexedColors.RED.getIndex());
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static void applyBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    /**
     * 行写入器。必须在 try-with-resources 中使用 —— {@link #close()} 负责
     * 删除磁盘临时文件，漏掉就会在系统临时目录里持续堆积垃圾文件。
     */
    public static final class SheetWriter implements AutoCloseable {

        private final SXSSFWorkbook workbook;
        private final CellStyle bodyStyle;
        /** 下一个可写的物理行号（含表头与提示行） */
        private int rowIndex;
        /** 已写入的数据行数，与 rowIndex 分开：rowIndex 还包含表头与提示行 */
        private int dataRowCount;

        private SheetWriter(SXSSFWorkbook workbook, CellStyle bodyStyle, int firstDataRow) {
            this.workbook = workbook;
            this.bodyStyle = bodyStyle;
            this.rowIndex = firstDataRow;
        }

        /** 已写入的数据行数（不含表头与提示行） */
        public int getDataRowCount() {
            return dataRowCount;
        }

        /** 还能再写多少行数据 */
        public int getRemainingCapacity() {
            return MAX_ROWS_PER_SHEET - rowIndex;
        }

        /**
         * 写一行数据。作为 {@code java.util.function.Predicate} 传给 Service，
         * 返回 false 即表示"写不下了，请停止"，由 Service 中断遍历。
         *
         * @return true 写入成功；false 已达 Excel 行数上限
         */
        public boolean write(TicketVO vo) {
            if (getRemainingCapacity() <= 0) {
                return false;
            }
            Row row = workbook.getSheetAt(0).createRow(rowIndex++);
            for (int i = 0; i < COLUMNS.size(); i++) {
                String value = COLUMNS.get(i).getter().apply(vo);
                Cell cell = row.createCell(i);
                // 空值写成空字符串而不是留空：用户导出后常按"是否为空"筛选，
                // 空字符串在各版本 Excel 中的行为比真正的空白单元格更一致。
                cell.setCellValue(value == null ? "" : value);
                cell.setCellStyle(bodyStyle);
            }
            dataRowCount++;
            return true;
        }

        /**
         * 把内容写成 zip 输出到目标流。整张表只调用一次，且必须在 {@link #close()} 之前。
         * 调用之后响应头才会被真正发出去，这是错误处理能保持正确的前提。
         */
        public void flushTo(OutputStream target) throws IOException {
            workbook.write(target);
        }

        /**
         * 释放工作簿并删除 SXSSF 在磁盘上的临时文件。
         *
         * <p><b>dispose() 不能省。</b>SXSSF 的临时文件不随 close() 自动删除。
         * 若漏掉，每导出一次就在系统临时目录留一个几 MB 到几百 MB 的文件，
         * 长期运行的服务最终会把磁盘填满 —— 这个问题在开发机上几乎发现不了，
         * 因为开发机重启频繁、导出次数少。</p>
         */
        @Override
        public void close() throws IOException {
            try {
                workbook.close();
            } finally {
                workbook.dispose();
            }
        }
    }
}
