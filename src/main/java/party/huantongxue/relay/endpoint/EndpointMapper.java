package party.huantongxue.relay.endpoint;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 所有查询都带 tenant_id 且排除软删除，租户之间互相看不到。 */
@Mapper
public interface EndpointMapper {

    void insert(Endpoint endpoint);

    Endpoint findById(@Param("tenantId") long tenantId, @Param("id") long id);

    List<Endpoint> findByTenant(long tenantId);

    /** 扇出用：该租户启用中的端点。 */
    List<Endpoint> findEnabledByTenant(long tenantId);

    int update(Endpoint endpoint);

    int updateSecret(@Param("tenantId") long tenantId, @Param("id") long id, @Param("secret") byte[] secret);

    int softDelete(@Param("tenantId") long tenantId, @Param("id") long id);
}
