package shipsensor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 航线几何（抽稀后的经纬度序列），供地图 / 数字地球总览渲染。
 *
 * pts 为 [longitude, latitude] 数组序列，按 stride 抽稀，
 * 单条航线通常只有几个到十几个点，整体响应可控制在百 KB 级。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RouteGeometry {

    private Long id;

    private String name;

    /** 抽稀后的坐标序列，元素为 [longitude, latitude] */
    private List<double[]> pts;

    /** 该航线的原始点位总数（抽稀前），供统计口径使用 */
    private Integer n;

    /** 航线申报里程（海里），可能为空 */
    private Double distanceNm;

    public RouteGeometry(Long id, String name, List<double[]> pts, Integer n) {
        this.id = id;
        this.name = name;
        this.pts = pts;
        this.n = n;
    }
}
