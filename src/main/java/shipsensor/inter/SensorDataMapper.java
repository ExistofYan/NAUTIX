package shipsensor.inter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import shipsensor.entity.SensorData;

import java.util.List;
import java.util.Map;


@Mapper
public interface SensorDataMapper
        extends BaseMapper<SensorData> {


    void insertBatch(
            @Param("list") List<SensorData> list
    );

    @Select("SELECT id, ship_id, config_id, data_value, recorded_at FROM sensor_data WHERE ship_id = #{shipId} ORDER BY id DESC LIMIT #{limit}")
    List<SensorData> selectLatestTrendByShip(@Param("shipId") Integer shipId, @Param("limit") int limit);

    @Select("SELECT id, ship_id, config_id, data_value, recorded_at FROM sensor_data ORDER BY id DESC LIMIT #{limit}")
    List<SensorData> selectLatestTrend(@Param("limit") int limit);

    /**
     * 全站波动性统计：仅扫描最近 scan 行（主键范围），
     * 按 config_id 分组返回条数、标准差、均值，避免前端拉原始数据做聚合。
     * 结果按标准差降序只取前 top 条——sensor_data 有 2.6 亿行、
     * sensor_config 有 16 万条，不限量会让响应体和前端聚合一起爆掉。
     * 直接带出传感器名与船号：前端只加载了限量配置，靠本地 join 会大面积取不到名字。
     */
    @Select("SELECT d.config_id AS configId, MAX(c.sensor_name) AS sensorName, MAX(c.ship_id) AS shipId, " +
            "COUNT(*) AS cnt, STDDEV_POP(d.data_value) AS stdDev, AVG(d.data_value) AS mean " +
            "FROM sensor_data d LEFT JOIN sensor_config c ON c.id = d.config_id " +
            "WHERE d.id >= (SELECT COALESCE(MAX(id), 0) - #{scan} FROM sensor_data) " +
            "GROUP BY d.config_id " +
            "HAVING COUNT(*) >= 2 " +
            "ORDER BY stdDev DESC LIMIT #{top}")
    List<Map<String, Object>> selectVolatilityStats(@Param("scan") long scan, @Param("top") int top);

    /**
     * 单船波动性统计：取该船最近 scan 条读数（每船约 3 万条，走 ship_id 索引），
     * 按 config_id 分组。不能用全站主键窗口——那样每船只剩十几条样本。
     */
    @Select("SELECT t.config_id AS configId, MAX(c.sensor_name) AS sensorName, MAX(t.ship_id) AS shipId, " +
            "COUNT(*) AS cnt, STDDEV_POP(t.data_value) AS stdDev, AVG(t.data_value) AS mean " +
            "FROM (SELECT config_id, ship_id, data_value FROM sensor_data " +
            "      WHERE ship_id = #{shipId} ORDER BY id DESC LIMIT #{scan}) t " +
            "LEFT JOIN sensor_config c ON c.id = t.config_id " +
            "GROUP BY t.config_id " +
            "HAVING COUNT(*) >= 2 " +
            "ORDER BY stdDev DESC LIMIT #{top}")
    List<Map<String, Object>> selectVolatilityStatsByShip(@Param("shipId") Integer shipId,
                                                          @Param("scan") long scan,
                                                          @Param("top") int top);

}
