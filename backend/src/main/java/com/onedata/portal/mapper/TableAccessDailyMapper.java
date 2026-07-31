package com.onedata.portal.mapper;

import com.onedata.portal.dto.DashboardTableAccessAggregate;
import com.onedata.portal.dto.TableAccessAggregate;
import com.onedata.portal.dto.TableAccessTrendPoint;
import com.onedata.portal.entity.TableAccessDaily;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface TableAccessDailyMapper {

    void upsertBatch(@Param("items") List<TableAccessDaily> items);

    TableAccessAggregate selectTableAggregate(
            @Param("clusterId") Long clusterId,
            @Param("dbName") String dbName,
            @Param("tableName") String tableName,
            @Param("totalStart") LocalDate totalStart,
            @Param("recentStart") LocalDate recentStart,
            @Param("days7Start") LocalDate days7Start,
            @Param("days30Start") LocalDate days30Start);

    List<TableAccessTrendPoint> selectTableTrend(
            @Param("clusterId") Long clusterId,
            @Param("dbName") String dbName,
            @Param("tableName") String tableName,
            @Param("startDate") LocalDate startDate);

    List<DashboardTableAccessAggregate> selectDashboardAggregates(
            @Param("clusterIds") List<Long> clusterIds,
            @Param("hotStart") LocalDate hotStart,
            @Param("historyStart") LocalDate historyStart);

    @Select("SELECT MIN(access_date) FROM table_access_daily WHERE cluster_id = #{clusterId}")
    LocalDate selectEarliestAccessDate(@Param("clusterId") Long clusterId);

    @Delete("DELETE FROM table_access_daily WHERE access_date < #{threshold}")
    int deleteBefore(@Param("threshold") LocalDate threshold);
}
