package com.productrag.catalog.option;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The value a variant has for one option. Composite foreign keys in the database guarantee that the option belongs to
 * the variant's product and the value belongs to the option.
 */
@Entity
@Table(name = "variant_option_values")
@IdClass(VariantOptionValue.Key.class)
public class VariantOptionValue {

    @Id
    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Id
    @Column(name = "option_id", nullable = false)
    private UUID optionId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "option_value_id", nullable = false)
    private UUID optionValueId;

    protected VariantOptionValue() {
    }

    public VariantOptionValue(UUID variantId, UUID productId, UUID optionId, UUID optionValueId) {
        this.variantId = variantId;
        this.productId = productId;
        this.optionId = optionId;
        this.optionValueId = optionValueId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public UUID getOptionId() {
        return optionId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getOptionValueId() {
        return optionValueId;
    }

    public void setOptionValueId(UUID optionValueId) {
        this.optionValueId = optionValueId;
    }

    public static class Key implements Serializable {
        private UUID variantId;
        private UUID optionId;

        public Key() {
        }

        public Key(UUID variantId, UUID optionId) {
            this.variantId = variantId;
            this.optionId = optionId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Objects.equals(variantId, key.variantId)
                    && Objects.equals(optionId, key.optionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(variantId, optionId);
        }
    }
}
