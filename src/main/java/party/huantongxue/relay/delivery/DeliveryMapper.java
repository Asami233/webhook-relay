package party.huantongxue.relay.delivery;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeliveryMapper {

    /** 扇出：为一个事件的每个订阅端点各插一行 PENDING，立即可被抢。 */
    int insertPending(@Param("tenantId") long tenantId, @Param("eventId") long eventId,
                      @Param("endpointIds") List<Long> endpointIds);

    int countByEvent(long eventId);

    List<Delivery> findByEvent(@Param("tenantId") long tenantId, @Param("eventId") long eventId);
}
