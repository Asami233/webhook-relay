package party.huantongxue.relay.tenant;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TenantMapper {

    void insert(Tenant tenant);

    Tenant findById(long id);

    Tenant findByApiKeyHash(String apiKeyHash);

    List<Tenant> findAll();

    int updateApiKey(@Param("id") long id, @Param("hash") String hash, @Param("prefix") String prefix);
}
