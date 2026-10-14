package party.huantongxue.relay.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import party.huantongxue.relay.common.ApiException;

class EventTypesTest {

    @Test
    void wildcardMatchesEverything() {
        assertThat(EventTypes.matches("*", "order.created")).isTrue();
    }

    @Test
    void prefixPatternMatchesDescendantsOnly() {
        assertThat(EventTypes.matches("order.*", "order.created")).isTrue();
        assertThat(EventTypes.matches("order.*", "order.item.added")).isTrue();
        assertThat(EventTypes.matches("order.*", "order")).isFalse();
        assertThat(EventTypes.matches("order.*", "orders.created")).isFalse();
    }

    @Test
    void exactAndListPatterns() {
        assertThat(EventTypes.matches("invoice.paid,order.created", "order.created")).isTrue();
        assertThat(EventTypes.matches("invoice.paid,order.created", "order.cancelled")).isFalse();
    }

    @Test
    void normalizeDeduplicatesAndRejectsBadPatterns() {
        assertThat(EventTypes.normalizePatterns(List.of("order.*", " order.* ", "invoice.paid")))
                .isEqualTo("order.*,invoice.paid");
        assertThatThrownBy(() -> EventTypes.normalizePatterns(List.of("order.*.x"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> EventTypes.normalizePatterns(List.of("a,b"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> EventTypes.normalizePatterns(List.of())).isInstanceOf(ApiException.class);
    }

    @Test
    void eventTypeMustNotBeAPattern() {
        assertThatThrownBy(() -> EventTypes.requireValidType("order.*")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> EventTypes.requireValidType("")).isInstanceOf(ApiException.class);
        EventTypes.requireValidType("order.created");
    }
}
