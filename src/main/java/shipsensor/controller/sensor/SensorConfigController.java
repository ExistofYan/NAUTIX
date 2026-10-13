package shipsensor.controller.sensor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import shipsensor.entity.SensorConfig;
import shipsensor.service.SensorConfigService;

import java.util.List;

/**
 * 船舶传感器配置 Controller
 */
@RestController
@RequestMapping("/sensorConfig")
public class SensorConfigController {

    @Autowired
    private SensorConfigService sensorConfigService;

    /**
     * 根据 ID 查询单条传感器配置
     */
    @GetMapping("/{id}")
    public SensorConfig getById(@PathVariable Long id) {
        return sensorConfigService.getById(id);
    }

    /**
     * 查询传感器配置（不分页，适用于下拉框/树等场景）。
     * sensor_config 表有 16 万条（1 万艘船 × 16 类传感器），
     * 全量返回会让前端一次性构造十几万个响应式对象并渲染，直接冻结主线程。
     * 因此：带 shipId 时只返回该船配置（约 16 条）；
     * 不带时按主键升序限量返回，供全局列表/树做首屏。
     */
    @GetMapping
    public List<SensorConfig> getAll(
            @RequestParam(required = false) Integer shipId,
            @RequestParam(defaultValue = "2000") int limit
    ) {
        if (limit < 1) limit = 1;
        if (limit > 5000) limit = 5000;
        LambdaQueryWrapper<SensorConfig> wrapper = new LambdaQueryWrapper<>();
        if (shipId != null) {
            wrapper.eq(SensorConfig::getShipId, shipId);
        }
        wrapper.orderByAsc(SensorConfig::getId);
        wrapper.last("LIMIT " + limit);
        return sensorConfigService.list(wrapper);
    }

    /**
     * 传感器配置总数。
     * 列表接口为性能已限量，KPI 需要真实总数时走这个 COUNT 端点，
     * 避免前端为了一个数字拉 16 万条数据。
     */
    @GetMapping("/count")
    public long count(@RequestParam(required = false) Integer shipId,
                      @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<SensorConfig> wrapper = new LambdaQueryWrapper<>();
        if (shipId != null) wrapper.eq(SensorConfig::getShipId, shipId);
        if (status != null) wrapper.eq(SensorConfig::getStatus, status);
        return sensorConfigService.count(wrapper);
    }

    /**
     * 新增单条传感器配置
     */
    @PostMapping
    public boolean save(@RequestBody SensorConfig sensorConfig) {
        return sensorConfigService.save(sensorConfig);
    }

    /**
     * 批量新增传感器配置（例如：一键为某艘船安装多个传感器）
     */
    @PostMapping("/batch")
    public boolean saveBatch(@RequestBody List<SensorConfig> sensorConfigList) {
        return sensorConfigService.saveBatch(sensorConfigList);
    }

    /**
     * 更新传感器配置（根据 ID 更新）
     */
    @PutMapping
    public boolean update(@RequestBody SensorConfig sensorConfig) {
        return sensorConfigService.updateById(sensorConfig);
    }

    /**
     * 根据 ID 删除单条传感器配置
     */
    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return sensorConfigService.removeById(id);
    }

    /**
     * 复杂分页查询（支持多条件筛选 + 动态排序）
     */
    @GetMapping("/list")
    public IPage<SensorConfig> getPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int count,
            @RequestParam(required = false) Integer shipId,
            @RequestParam(required = false) Integer typeId,
            @RequestParam(required = false) String sensorName,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder
    ) {
        // 防止单次查询数据量过大
        if (count > 100) count = 100;

        LambdaQueryWrapper<SensorConfig> wrapper = new LambdaQueryWrapper<>();

        // 1. 精确匹配：根据船舶ID筛选
        if (shipId != null) {
            wrapper.eq(SensorConfig::getShipId, shipId);
        }
        // 2. 精确匹配：根据传感器类型ID筛选
        if (typeId != null) {
            wrapper.eq(SensorConfig::getTypeId, typeId);
        }
        // 3. 模糊匹配：根据传感器别名搜索
        if (sensorName != null && !sensorName.isBlank()) {
            wrapper.like(SensorConfig::getSensorName, sensorName);
        }
        // 4. 精确匹配：根据在线/离线状态筛选
        if (status != null) {
            wrapper.eq(SensorConfig::getStatus, status);
        }

        // 5. 应用动态排序
        applySort(wrapper, sortField, sortOrder);

        return sensorConfigService.page(new Page<>(page, count), wrapper);
    }

    /**
     * 私有方法：处理动态排序（白名单机制，防止 SQL 注入风险）
     */
    private void applySort(LambdaQueryWrapper<SensorConfig> wrapper, String sortField, String sortOrder) {
        if (sortField == null || sortField.isBlank()) return;

        // 只允许对这几个安全字段进行排序
        String[] allowed = {"id", "shipId", "typeId", "installDate", "status", "createTime"};
        boolean ok = false;
        for (String f : allowed) {
            if (f.equals(sortField)) {
                ok = true;
                break;
            }
        }
        if (!ok) return;

        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        switch (sortField) {
            case "id":
                wrapper.orderBy(true, isAsc, SensorConfig::getId);
                break;
            case "shipId":
                wrapper.orderBy(true, isAsc, SensorConfig::getShipId);
                break;
            case "typeId":
                wrapper.orderBy(true, isAsc, SensorConfig::getTypeId);
                break;
            case "installDate":
                wrapper.orderBy(true, isAsc, SensorConfig::getInstallDate);
                break;
            case "status":
                wrapper.orderBy(true, isAsc, SensorConfig::getStatus);
                break;
            case "createTime":
                wrapper.orderBy(true, isAsc, SensorConfig::getCreateTime);
                break;
        }
    }
}