package shipsensor.controller.sensor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import shipsensor.entity.SensorData;
import shipsensor.inter.SensorDataMapper;
import shipsensor.service.SensorDataService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/sensorData")
public class SensorDataController {

    @Autowired
    private SensorDataService sensorDataService;

    @Autowired
    private SensorDataMapper sensorDataMapper;

    /**
     * 最近读数趋势。
     * sensor_data 有 2.6 亿行、每艘船 16 类传感器且读数按船轮转写入，
     * 不带 shipId 的全局最新 N 条几乎全是不同 config（每路只有 1~2 个点），
     * 画不出趋势线；因此前端应按船取数，单船 limit 允许更大。
     */
    @GetMapping("/trend")
    public List<SensorData> getTrend(
            @RequestParam(defaultValue = "400") int limit,
            @RequestParam(required = false) Integer shipId) {
        if (limit < 1) limit = 1;
        if (shipId != null) {
            if (limit > 5000) limit = 5000;
            return sensorDataMapper.selectLatestTrendByShip(shipId, limit);
        }
        if (limit > 400) limit = 400;
        return sensorDataMapper.selectLatestTrend(limit);
    }

    /**
     * 传感器波动性统计（数据库级聚合）。
     * scan 为扫描的最近行数上限，默认 20 万行；top 为返回的 config 数上限。
     * sensor_data 有 2.6 亿行，必须同时限制扫描窗口与结果条数，
     * 否则响应体和前端 join 会一起把页面拖死。
     * shipId 非空时只统计该船（走 ship_id 索引，窗口可放大）。
     */
    @GetMapping("/volatility")
    public List<java.util.Map<String, Object>> getVolatility(
            @RequestParam(defaultValue = "200000") long scan,
            @RequestParam(defaultValue = "300") int top,
            @RequestParam(required = false) Integer shipId) {
        if (top < 10) top = 10;
        if (top > 1000) top = 1000;
        if (shipId != null) {
            // 单船：scan 表示"该船最近多少条读数"，每船约 2.6 万条，默认扫 5000 足够出稳定统计
            if (scan < 1000) scan = 5000;
            if (scan > 50000) scan = 50000;
            return sensorDataMapper.selectVolatilityStatsByShip(shipId, scan, top);
        }
        if (scan < 1000) scan = 1000;
        if (scan > 2_000_000) scan = 2_000_000;
        return sensorDataMapper.selectVolatilityStats(scan, top);
    }

    @GetMapping("/{id}")
    public SensorData getById(@PathVariable Long id) {
        return sensorDataService.getById(id);
    }

    /**
     * 传感器读数列表（限量保护）。
     * 该表 2.6 亿行，无 LIMIT 的 list 一旦被调用就是几十秒起步、
     * 且会把整表塞进 JVM 堆，故强制上限。
     */
    @GetMapping("/list")
    public List<SensorData> getAll(
            @RequestParam(required = false) Integer shipId,
            @RequestParam(required = false) Integer configId,
            @RequestParam(defaultValue = "500") int limit) {
        if (limit < 1) limit = 1;
        if (limit > 5000) limit = 5000;
        LambdaQueryWrapper<SensorData> wrapper = new LambdaQueryWrapper<>();
        if (shipId != null) wrapper.eq(SensorData::getShipId, shipId);
        if (configId != null) wrapper.eq(SensorData::getConfigId, configId);
        wrapper.orderByDesc(SensorData::getId);
        wrapper.last("LIMIT " + limit);
        return sensorDataService.list(wrapper);
    }

    @PostMapping
    public boolean save(@RequestBody SensorData sensorData) {
        return sensorDataService.save(sensorData);
    }

    @PostMapping("/batch")
    public boolean saveBatch(@RequestBody List<SensorData> dataList) {
        return sensorDataService.saveBatch(dataList);
    }

    @PutMapping
    public boolean update(@RequestBody SensorData sensorData) {
        return sensorDataService.updateById(sensorData);
    }

    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return sensorDataService.removeById(id);
    }

    /**
     * 分页查询（性能优化版）。
     *
     * sensor_data 表数据量达数亿且 recorded_at 无索引，
     * 直接按时间范围 ORDER BY + COUNT 会全表扫描导致超时。
     * 优化策略：
     *  1. 数据按主键自增顺序写入，"最新数据"等价于"最大主键"，
     *     因此一律 ORDER BY id DESC，走主键聚簇索引，毫秒级返回；
     *  2. 带 startTime 的查询，先用主键采样估算写入速率，
     *     把时间窗口换算成主键下界 id >= lower，大幅缩小扫描范围；
     *  3. 跳过全表 COUNT（setSearchCount(false)），total 用速率估算值展示。
     */
    @GetMapping("/page")
    public IPage<SensorData> getPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Integer shipId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime
    ) {
        if (size > 100) size = 100;

        Page<SensorData> pg = new Page<>(page, size);
        pg.setSearchCount(false);

        LocalDateTime start = parseTime(startTime);
        LocalDateTime end = parseTime(endTime);

        LambdaQueryWrapper<SensorData> wrapper = new LambdaQueryWrapper<>();
        if (shipId != null) {
            wrapper.eq(SensorData::getShipId, shipId);
        }

        double ratePerSec = 1.0;
        Long topId = null;
        long[] sample = sampleRecentRate();
        if (sample != null) {
            topId = sample[0];
            ratePerSec = Math.max(sample[1], 0.1);
        }

        if (start != null && topId != null) {
            // 时间窗口 -> 主键下界（1.5 倍安全余量，回溯最多 2000 万行）
            long secondsBack = Duration.between(start, LocalDateTime.now()).getSeconds();
            if (secondsBack > 0) {
                long delta = Math.min((long) (secondsBack * ratePerSec * 1.5) + 1000, 20_000_000L);
                wrapper.ge(SensorData::getId, Math.max(1, topId - delta));
            }
        }
        if (start != null) {
            wrapper.ge(SensorData::getRecordedAt, start);
        }
        if (end != null) {
            wrapper.le(SensorData::getRecordedAt, end);
        }

        wrapper.orderByDesc(SensorData::getId);

        IPage<SensorData> result = sensorDataService.page(pg, wrapper);

        // 估算 total 供分页展示（不再全表 COUNT）
        if (start != null) {
            long secondsBack = Math.max(Duration.between(start, end != null ? end : LocalDateTime.now()).getSeconds(), 0);
            result.setTotal(Math.min((long) (secondsBack * ratePerSec), topId != null ? topId : Long.MAX_VALUE));
        } else {
            result.setTotal(topId != null ? topId : result.getRecords().size());
        }
        return result;
    }

    /** 主键采样：返回 {当前最大主键, 平均每秒写入条数}，采样失败返回 null */
    private long[] sampleRecentRate() {
        try {
            List<SensorData> latest = sensorDataMapper.selectList(
                    new LambdaQueryWrapper<SensorData>()
                            .orderByDesc(SensorData::getId)
                            .last("LIMIT 1"));
            if (latest.isEmpty()) return null;
            SensorData top = latest.get(0);
            if (top.getId() == null || top.getRecordedAt() == null) return null;

            long sampleGap = 100_000L;
            long baseId = Math.max(1, top.getId() - sampleGap);
            SensorData base = sensorDataMapper.selectById(baseId);

            double ratePerSec;
            if (base != null && base.getRecordedAt() != null) {
                double secs = Duration.between(base.getRecordedAt(), top.getRecordedAt()).getSeconds();
                ratePerSec = secs > 0 ? sampleGap / secs : sampleGap;
            } else {
                ratePerSec = 1.0;
            }
            return new long[]{top.getId(), (long) ratePerSec};
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseTime(String time) {
        if (time == null || time.isBlank()) return null;
        try {
            return LocalDateTime.parse(time.replace(" ", "T"));
        } catch (Exception e) {
            return null;
        }
    }
}
