package com.enterprise.workorder.service;

import com.enterprise.workorder.dto.TicketQuery;
import com.enterprise.workorder.dto.TicketVO;
import com.enterprise.workorder.excel.TicketExcelWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 工单导出服务。
 *
 * <p><b>为什么导出放在后端而不是前端用 SheetJS 生成</b>：
 * 前端生成只能拿到"当前这一页"的数据。要导全量，前端得自己循环调分页接口 ——
 * 而分页接口有 pageSize 上限 200，导出 5 万行就要发 250 个请求，
 * 每个请求都是一次完整的鉴权、查询、序列化，浏览器还要把这些数据全部留在内存里。
 * 后端导出则是从数据库游标一批批取、直接写进 HTTP 响应流，内存占用恒定，
 * 也不受分页上限约束。分页可以有上限（那是给人看的），导出不该有。</p>
 *
 * <p><b>为什么这个类不直接接收 HttpServletResponse</b>：让 Service 层依赖 Web 层类型，
 * 会让它无法被单测直接调用，也把"写 HTTP 响应头"这种容易写错的细节混进业务逻辑。
 * 这里只暴露一个"往任意 OutputStream 里写 xlsx"的方法，
 * 由 Controller 负责决定写到哪里、以及响应头怎么设。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketExportService {

    /**
     * 每批从数据库取多少行。
     *
     * <p>500 是个折中：太小则网络往返次数多（每批都是一次完整 SQL + 4 次关联查询），
     * 太大则这批 VO 在内存里的占用变大，抵消了流式导出的意义。
     * 按每行约 1KB 估算，500 行约 0.5MB，是安全的。</p>
     */
    private static final int BATCH_SIZE = 500;

    /**
     * 导出上限。超过后只导出最新的这么多行，并在文件里写明。
     *
     * <p>设上限不是为了保护数据库（游标翻页本身不慢），而是为了不让一次误操作
     * 生成一个几百 MB 的文件把用户的浏览器卡死。真要导全量，正确做法是缩小筛选范围后
     * 分几次导，而不是把上限一路调高。</p>
     *
     * <p><b>为什么做成配置项而不是常量</b>：这个上限只在"数据真的超过它"时才会生效，
     * 也就是说，如果它写死在代码里，那段截断提示的代码在本机几乎永远跑不到 ——
     * 而没被执行过的代码就是没被验证过的代码。做成配置项后，测试可以用
     * {@code -Dworkorder.export.max-rows=2} 启动服务，真实地把截断分支跑一遍。</p>
     */
    @Value("${workorder.export.max-rows:100000}")
    private int exportLimit = 100_000;

    private static final DateTimeFormatter FILE_SUFFIX_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TicketService ticketService;

    /** 导出文件名，形如 工单导出_20260920153012.xlsx */
    public String buildFileName() {
        return "工单导出_" + LocalDateTime.now().format(FILE_SUFFIX_FMT) + ".xlsx";
    }

    /**
     * 把符合条件的工单写进 {@code out}。
     *
     * @return 实际写出的数据行数
     */
    public int export(TicketQuery query, OutputStream out) throws IOException {
        long total = ticketService.countForExport(query);
        String notice = buildNotice(total);
        int written = 0;
        Long lastId = null;

        try (TicketExcelWriter.SheetWriter writer = TicketExcelWriter.createSheet(notice)) {
            while (written < exportLimit && writer.getRemainingCapacity() > 0) {
                // 最后一批可能装不满，按剩余额度收窄，避免多取
                int batchSize = Math.min(BATCH_SIZE, exportLimit - written);
                List<TicketVO> batch = ticketService.exportBatch(query, lastId, batchSize);
                if (batch.isEmpty()) {
                    break;
                }

                boolean outOfRows = false;
                for (TicketVO vo : batch) {
                    if (!writer.write(vo)) {
                        outOfRows = true;
                        break;
                    }
                    written++;
                }
                if (outOfRows) {
                    log.warn("已达 Excel 单表行数上限，实际写出 {} 行", written);
                    break;
                }

                lastId = batch.get(batch.size() - 1).getId();
                if (batch.size() < batchSize) {
                    // 最后一批，再查一次只会得到空结果
                    break;
                }
            }

            // 先写完内容，再退出 try 块触发 close()。顺序颠倒会导出空文件。
            writer.flushTo(out);
            out.flush();
        }

        if (written == 0) {
            log.info("导出完成：符合条件的数据为 0 条，已生成仅含表头的文件");
        } else {
            log.info("导出完成：写出 {} 行（符合条件的总数 {}）", written, total);
        }
        return written;
    }

    /**
     * 生成提示语。
     *
     * <p>只在真正会丢数据时才写提示。若总行数没超限，用户拿到的就是一份干净的表格，
     * 不必在第一行看到一句多余的"本次共导出 37 行"。</p>
     */
    private String buildNotice(long total) {
        int capacity = TicketExcelWriter.MAX_ROWS_PER_SHEET - 1;
        int limit = Math.min(exportLimit, capacity);
        if (total <= limit) {
            return null;
        }
        return "提示：符合条件的工单共 " + total + " 条，本次仅导出最新的 " + limit
                + " 条。如需完整数据，请缩小筛选范围后分批导出。";
    }
}
