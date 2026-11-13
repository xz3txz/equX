package com.xz3t.equX.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.xz3t.equX.ui.theme.AppUiTheme

/**
 * Универсальная главная внешняя карточка приложений equX для всех полос и панелей.
 * Поддерживает onClick для анимации нажатия (Ripple) на всю площадь.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquXMainCard(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    shape: Shape = RoundedCornerShape(AppUiTheme.mainCardCornerRadius),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) AppUiTheme.activeHeaderColor() else AppUiTheme.inactiveHeaderColor()
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = AppUiTheme.cardElevation)
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = AppUiTheme.cardInnerPaddingHorizontal,
                    vertical = AppUiTheme.cardInnerPaddingVertical
                ),
                content = content
            )
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (isActive) AppUiTheme.activeHeaderColor() else AppUiTheme.inactiveHeaderColor()
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = AppUiTheme.cardElevation)
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = AppUiTheme.cardInnerPaddingHorizontal,
                    vertical = AppUiTheme.cardInnerPaddingVertical
                ),
                content = content
            )
        }
    }
}

/**
 * Внутренняя под-карточка для параметров
 */
@Composable
fun EquXSubCard(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    shape: Shape = RoundedCornerShape(AppUiTheme.subCardCornerRadius),
    content: @Composable RowScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) AppUiTheme.activeSubCardColor() else AppUiTheme.inactiveSubCardColor()
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = AppUiTheme.cardElevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AppUiTheme.cardInnerPaddingHorizontal,
                    vertical = AppUiTheme.cardInnerPaddingVertical
                ),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
