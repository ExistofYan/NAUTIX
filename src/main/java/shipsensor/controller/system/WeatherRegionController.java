package shipsensor.controller.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import shipsensor.entity.WeatherRegion;
import shipsensor.service.WeatherRegionService;

import java.util.List;

@RestController
@RequestMapping("/weather")
@RequiredArgsConstructor
public class WeatherRegionController {

    private final WeatherRegionService weatherService;

    /**
     * 区域列表。
     *
     * weather_region 会随模拟运行持续增长，全量返回会让单次响应达到
     * 数十 MB，浏览器解析加图层渲染必然卡死，因此这里按 limit 限量返回
     * 最近的区域（后端已按 id 倒序，等价于最新写入优先）。
     *
     * @param limit 最多返回多少条（默认 400，上限 2000）
     */
    @GetMapping
    public List<WeatherRegion> list(@RequestParam(defaultValue = "400") int limit) {
        if (limit < 1) limit = 1;
        if (limit > 2000) limit = 2000;
        LambdaQueryWrapper<WeatherRegion> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(WeatherRegion::getId);
        wrapper.last("LIMIT " + limit);
        return weatherService.list(wrapper);
    }

    @GetMapping("/{id}")
    public WeatherRegion get(@PathVariable Long id) {
        return weatherService.getById(id);
    }

    @PostMapping
    public boolean save(@RequestBody WeatherRegion weather) {
        return weatherService.save(weather);
    }

    @PutMapping
    public boolean update(@RequestBody WeatherRegion weather) {
        return weatherService.updateById(weather);
    }

    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        return weatherService.removeById(id);
    }

}
