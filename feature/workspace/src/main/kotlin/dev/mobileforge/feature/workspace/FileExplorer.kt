package dev.mobileforge.feature.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileforge.core.designsystem.icon.ForgeIcons
import dev.mobileforge.core.designsystem.theme.LocalForgeCodeTypography
import dev.mobileforge.core.model.DefaultExclusions
import dev.mobileforge.core.model.WorkspaceFile

/**
 * The project file tree.
 *
 * Renders a flattened view of only the directories the user has expanded, so the list stays
 * proportional to what is on screen rather than to the size of the repository. Opening a
 * Laravel project with vendor/ present must not cost anything until someone actually opens
 * vendor/.
 */
@Composable
fun FileExplorer(
    tree: TreeState,
    selectedFile: String?,
    showHiddenFiles: Boolean,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(tree, showHiddenFiles) {
        flattenTree(tree, showHiddenFiles)
    }

    LazyColumn(modifier = modifier) {
        items(
            count = rows.size,
            key = { index -> rows[index].file.relativePath },
        ) { index ->
            val row = rows[index]
            FileRow(
                row = row,
                isSelected = row.file.relativePath == selectedFile,
                isLoading = row.file.relativePath in tree.loading,
                isExpanded = row.file.relativePath in tree.expanded,
                onClick = {
                    if (row.file.isDirectory) {
                        onToggleDirectory(row.file.relativePath)
                    } else {
                        onSelectFile(row.file.relativePath)
                    }
                },
            )
        }
    }
}

@Composable
private fun FileRow(
    row: TreeRow,
    isSelected: Boolean,
    isLoading: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val isExcluded = row.file.isDirectory && DefaultExclusions.isExcluded(row.file.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            // 40dp keeps the row a comfortable touch target while staying dense enough that a
            // phone screen shows a useful amount of the tree.
            .height(40.dp)
            .padding(start = (8 + row.depth * 14).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                )

                row.file.isDirectory -> Icon(
                    imageVector = if (isExpanded) ForgeIcons.ChevronDown else ForgeIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        Icon(
            imageVector = if (row.file.isDirectory) ForgeIcons.Folder else ForgeIcons.File,
            contentDescription = null,
            tint = when {
                isExcluded -> MaterialTheme.colorScheme.outline
                row.file.isDirectory -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp),
        )

        Spacer(Modifier.width(8.dp))

        Text(
            text = row.file.name,
            style = LocalForgeCodeTypography.current.code,
            color = when {
                // Excluded directories are dimmed, not hidden: the user can still open
                // node_modules deliberately, they just are not encouraged to.
                isExcluded -> MaterialTheme.colorScheme.outline
                row.file.isHidden -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private data class TreeRow(val file: WorkspaceFile, val depth: Int)

/**
 * Depth-first flatten of the expanded subtree, in display order.
 *
 * Iterative rather than recursive: a deliberately-crafted deep directory chain in a hostile
 * repository must not be able to overflow the stack while drawing a file list. Children are
 * pushed in reverse so they pop back in the order the filesystem layer sorted them.
 */
private fun flattenTree(tree: TreeState, showHidden: Boolean): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    val stack = ArrayDeque<TreeRow>()

    fun pushChildrenOf(path: String, childDepth: Int) {
        val children = tree.children[path] ?: return
        for (child in children.asReversed()) {
            if (child.isHidden && !showHidden) continue
            stack.addLast(TreeRow(child, childDepth))
        }
    }

    pushChildrenOf(path = "", childDepth = 0)

    while (stack.isNotEmpty()) {
        val row = stack.removeLast()
        out.add(row)
        if (row.file.isDirectory && row.file.relativePath in tree.expanded) {
            pushChildrenOf(row.file.relativePath, row.depth + 1)
        }
    }

    return out
}
