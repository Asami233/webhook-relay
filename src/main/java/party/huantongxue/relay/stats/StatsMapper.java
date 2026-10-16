package party.huantongxue.relay.stats;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import party.huantongxue.relay.delivery.StatusCount;

@Mapper
public interface StatsMapper {

    List<StatusCount> countByStatus(long tenantId);

    long countEvents(long tenantId);

    /** 最近 minutes 分钟内每分钟的成功/失败尝试数（没有尝试的分钟不返回，由调用方补零）。 */
    List<MinuteCount> attemptsPerMinute(@Param("tenantId") long tenantId, @Param("minutes") int minutes);
}
