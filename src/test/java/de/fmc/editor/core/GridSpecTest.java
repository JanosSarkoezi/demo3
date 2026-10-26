package de.fmc.editor.core;

import de.fmc.editor.core.model.GridSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GridSpecTest {

    private final GridSpec grid = new GridSpec(10.0);

    @Test
    @DisplayName("snapToGrid rundet auf den nächsten Grid-Schnittpunkt")
    void snapToGridShouldRoundToNearestIntersection() {
        assertThat(grid.snapToGrid(100.0)).isEqualTo(100.0);
        assertThat(grid.snapToGrid(103.0)).isEqualTo(100.0);
        assertThat(grid.snapToGrid(107.0)).isEqualTo(110.0);
        assertThat(grid.snapToGrid(-6.0)).isEqualTo(-10.0);
    }

    @Test
    @DisplayName("Nicht-positive Grid-Größen werden abgelehnt")
    void gridSizeMustBePositive() {
        assertThatThrownBy(() -> new GridSpec(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GridSpec(-10.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
