package party.huantongxue.relay.event;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EventMapper {

    /** 违反 UNIQUE(tenant_id, idempotency_key) 时抛 DuplicateKeyException，由调用方转为幂等重放。 */
    void insert(Event event);

    Event findById(@Param("tenantId") long tenantId, @Param("id") long id);

    Event findByIdempotencyKey(@Param("tenantId") long tenantId, @Param("key") String key);

    /** 键集分页：beforeId 为 null 时从最新开始。 */
    List<Event> findPage(@Param("tenantId") long tenantId, @Param("beforeId") Long beforeId,
                         @Param("limit") int limit);
}
