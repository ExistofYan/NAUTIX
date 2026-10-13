package shipsensor.controller.ship;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import shipsensor.dto.RouteGeometry;
import shipsensor.entity.Route;
import shipsensor.entity.RoutePoint;
import shipsensor.service.RouteService;
import shipsensor.simulator.cache.RouteCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@RestController//这个类被@RestController标注，它是被容器管理的类bean
@RequestMapping("/route")
@RequiredArgsConstructor//自动创建构造器
public class RouteController {


    private final RouteService routeService;//依赖注入

    /** 航线与点位内存缓存，几何总览接口直接读取，避免全量查库 */
    private final RouteCache routeCache;
    //

    /**
     * 分页查询航线
     */
    @GetMapping("/page")
    public IPage<Route> page(

            @RequestParam(defaultValue = "1")
            int page,


            @RequestParam(defaultValue = "10")
            int count,


            @RequestParam(required = false)
            String keyword,


            @RequestParam(required = false)
            Long startPortId,


            @RequestParam(required = false)
            Long endPortId,


            @RequestParam(required = false)
            String sortField,


            @RequestParam(required = false)
            String sortOrder

    ){


        if(count > 100){

            count = 100;

        }


        LambdaQueryWrapper<Route> wrapper =
                new LambdaQueryWrapper<>();



        /**
         * 航线名称搜索
         */
        if(keyword != null && !keyword.isBlank()){


            wrapper.like(
                    Route::getRouteName,
                    keyword
            );


        }



        /**
         * 起始港口过滤
         */
        if(startPortId != null){

            wrapper.eq(
                    Route::getStartPortId,
                    startPortId
            );

        }



        /**
         * 目的港过滤
         */
        if(endPortId != null){

            wrapper.eq(
                    Route::getEndPortId,
                    endPortId
            );

        }



        applySort(
                wrapper,
                sortField,
                sortOrder
        );



        return routeService.page(

                new Page<>(page,count),

                wrapper

        );

    }





    /**
     * 根据ID查询
     */
    @GetMapping("/{id}")
    public Route get(
            @PathVariable Long id
    ){

        return routeService.getById(id);

    }





    /**
     * 新增
     */
    @PostMapping
    public boolean save(
            @RequestBody Route route
    ){

        return routeService.save(route);

    }





    /**
     * 修改
     */
    @PutMapping
    public boolean update(
            @RequestBody Route route
    ){

        return routeService.updateById(route);

    }





    /**
     * 删除
     */
    @DeleteMapping("/{id}")
    public boolean delete(
            @PathVariable Long id
    ){

        return routeService.removeById(id);

    }





    /**
     * 航线几何总览（供地图 / 数字地球渲染）。
     *
     * 直接返回全量 route_point 会有上百 MB，浏览器解析必然卡死，
     * 因此这里基于内存缓存做抽稀：每条航线最多输出 ceil(n/stride) 个
     * 点（始终保留首尾点），并限制单次返回的航线条数。
     * 单条航线的完整点位可用 ids 参数精确获取。
     *
     * @param limit  最多返回多少条航线（默认 400，上限 2000）
     * @param stride 抽稀步长，每 stride 个原始点取 1 个（默认 4，最小 1）
     * @param ids    可选：逗号分隔的航线 id。给出时只返回这些航线的完整点位
     *               （不再受 limit 限制），供详情页 / 回放精确取数
     */
    @GetMapping("/geometry")
    public List<RouteGeometry> geometry(
            @RequestParam(defaultValue = "400") int limit,
            @RequestParam(defaultValue = "4") int stride,
            @RequestParam(required = false) String ids
    ) {
        if (stride < 1) stride = 1;

        List<RouteGeometry> out = new ArrayList<>();
        Map<Long, List<RoutePoint>> pointMap = routeCache.getPointMap();

        if (ids != null && !ids.isBlank()) {
            for (String raw : ids.split(",")) {
                Long routeId = parseId(raw);
                if (routeId == null) continue;
                RouteGeometry g = buildGeometry(routeId, pointMap.get(routeId), routeCache.getRoutes(), 1);
                if (g != null) out.add(g);
            }
            return out;
        }

        if (limit < 1) limit = 1;
        if (limit > 2000) limit = 2000;

        int taken = 0;
        for (Route route : routeCache.getRoutes()) {
            if (taken >= limit) break;
            if (route == null || route.getId() == null) continue;
            RouteGeometry g = buildGeometry(route.getId(), pointMap.get(route.getId()), null, stride);
            if (g == null) continue;
            g.setName(route.getRouteName());
            g.setDistanceNm(route.getDistanceNm());
            out.add(g);
            taken++;
        }
        return out;
    }


    /**
     * 由缓存点位构造抽稀几何；pointsOfRoute 为空或不足 2 个有效点时返回 null。
     * routes 仅在需要按 id 反查航线名时传入（可为 null）。
     */
    private RouteGeometry buildGeometry(
            Long routeId,
            List<RoutePoint> pts,
            List<Route> routes,
            int stride
    ) {
        if (routeId == null || pts == null || pts.size() < 2) return null;

        List<double[]> thin = new ArrayList<>();
        for (int i = 0; i < pts.size(); i += stride) {
            RoutePoint p = pts.get(i);
            if (p != null && p.getLongitude() != null && p.getLatitude() != null) {
                thin.add(new double[]{p.getLongitude(), p.getLatitude()});
            }
        }
        // 始终补上终点，避免抽稀后航向被改变
        RoutePoint last = pts.get(pts.size() - 1);
        if (last != null && last.getLongitude() != null && last.getLatitude() != null) {
            double[] tail = new double[]{last.getLongitude(), last.getLatitude()};
            if (thin.isEmpty() || thin.get(thin.size() - 1)[0] != tail[0]
                    || thin.get(thin.size() - 1)[1] != tail[1]) {
                thin.add(tail);
            }
        }
        if (thin.size() < 2) return null;

        String name = null;
        Double declared = null;
        if (routes != null) {
            for (Route r : routes) {
                if (r != null && routeId.equals(r.getId())) {
                    name = r.getRouteName();
                    declared = r.getDistanceNm();
                    break;
                }
            }
        }
        RouteGeometry g = new RouteGeometry(routeId, name, thin, pts.size());
        g.setDistanceNm(declared);
        return g;
    }


    private Long parseId(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    /**
     * 排序
     */
    private void applySort(

            LambdaQueryWrapper<Route> wrapper,

            String sortField,

            String sortOrder

    ){


        if(sortField == null || sortField.isBlank()){

            return;

        }



        boolean asc =
                "asc".equalsIgnoreCase(sortOrder);



        switch(sortField){


            case "id":

                wrapper.orderBy(
                        true,
                        asc,
                        Route::getId
                );

                break;



            case "routeName":

                wrapper.orderBy(
                        true,
                        asc,
                        Route::getRouteName
                );

                break;



            case "distanceNm":

                wrapper.orderBy(
                        true,
                        asc,
                        Route::getDistanceNm
                );

                break;



        }


    }


}