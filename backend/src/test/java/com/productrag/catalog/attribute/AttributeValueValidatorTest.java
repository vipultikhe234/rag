package com.productrag.catalog.attribute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.productrag.catalog.CatalogStatus;
import com.productrag.common.exception.FieldValidationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AttributeValueValidatorTest {

    private static final UUID CATEGORY = UUID.randomUUID();

    @Mock
    private ProductAttributeService attributeService;

    private AttributeValueValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AttributeValueValidator(attributeService);
        when(attributeService.applicableDefinitions(CATEGORY, true)).thenReturn(List.of(
                AttributeTestData.attribute(CATEGORY, "RAM", "ram", AttributeDataType.NUMBER, List.of(), true,
                        CatalogStatus.ACTIVE),
                AttributeTestData.attribute(CATEGORY, "Screen Size", "screen-size", AttributeDataType.NUMBER,
                        List.of(), false, CatalogStatus.ACTIVE),
                AttributeTestData.attribute(CATEGORY, "Color", "color", AttributeDataType.SELECT,
                        List.of("Black", "Blue"), false, CatalogStatus.ACTIVE),
                AttributeTestData.attribute(CATEGORY, "Bluetooth Support", "bluetooth-support",
                        AttributeDataType.BOOLEAN, List.of(), false, CatalogStatus.ACTIVE),
                AttributeTestData.attribute(CATEGORY, "Dimensions", "dimensions", AttributeDataType.STRING,
                        List.of(), false, CatalogStatus.ACTIVE),
                AttributeTestData.attribute(CATEGORY, "Legacy Code", "legacy-code", AttributeDataType.STRING,
                        List.of(), false, CatalogStatus.INACTIVE)));
    }

    @Test
    void valuesAreConvertedToTheirDeclaredTypes() {
        Map<String, Object> result = validator.validate(CATEGORY, Map.of("ram", "8", "screen-size", "6.1",
                "color", "black", "bluetooth-support", "TRUE", "dimensions", " 15 x 7 cm "), Map.of());

        assertThat(result).containsEntry("ram", 8L)
                .containsEntry("screen-size", new BigDecimal("6.1"))
                .containsEntry("color", "Black")
                .containsEntry("bluetooth-support", true)
                .containsEntry("dimensions", "15 x 7 cm");
    }

    @Test
    void invalidValuesAreReportedPerAttribute() {
        assertThatThrownBy(() -> validator.validate(CATEGORY,
                Map.of("ram", "lots", "color", "Purple", "bluetooth-support", "maybe"), Map.of()))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getViolations())
                        .extracting(v -> v.field())
                        .containsExactlyInAnyOrder("attributes.ram", "attributes.color",
                                "attributes.bluetooth-support"));
    }

    @Test
    void requiredAttributesMustBePresentUnlessCheckingAPartialSet() {
        assertThatThrownBy(() -> validator.validate(CATEGORY, Map.of("color", "Blue"), Map.of()))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getViolations().getFirst().field())
                        .isEqualTo("attributes.ram"));

        assertThat(validator.validate(CATEGORY, Map.of("color", "Blue"), Map.of(), false))
                .containsEntry("color", "Blue");
    }

    @Test
    void inactiveAttributeKeepsItsExistingValueButCannotChange() {
        Map<String, Object> previous = Map.of("ram", 8L, "legacy-code", "A1");

        assertThat(validator.validate(CATEGORY, Map.of("ram", 8, "legacy-code", "A1"), previous))
                .containsEntry("legacy-code", "A1");
        assertThatThrownBy(() -> validator.validate(CATEGORY, Map.of("ram", 8, "legacy-code", "B2"), previous))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void undefinedKeysStayAllowedAsFreeFormAttributes() {
        assertThat(validator.validate(CATEGORY, Map.of("ram", 16, "battery_life", "30 hours"), Map.of()))
                .containsEntry("battery_life", "30 hours");
    }
}
