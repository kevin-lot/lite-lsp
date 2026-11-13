package me.kevinlot.lite.core

import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * Tells which code never changes during a session (dependencies, standard library) and what
 * invalidates that knowledge (e.g. a new crate graph, a new classpath).
 */
interface LibraryOracle {
    fun isLibrary(e: PsiElement): Boolean
    fun tracker(e: PsiElement): ModificationTracker

    /**
     * Libraries the platform does not know as such (cheap, file-based: used inside search scopes).
     * Default: none, the project's library roots already cover them.
     */
    fun isLibraryFile(project: com.intellij.openapi.project.Project, file: com.intellij.openapi.vfs.VirtualFile): Boolean = false
}

object LiteCache {
    /**
     * Memoizes [compute] on [e]. Results computed for library code survive edits of the project
     * (that is what keeps resolution fast while typing); project code is recomputed after any change.
     */
    fun <T> cached(e: PsiElement, key: Key<CachedValue<T>>, oracle: LibraryOracle, compute: () -> T): T =
        CachedValuesManager.getCachedValue(e, key) { CachedValueProvider.Result.create(compute(), *dependencies(e, oracle)) }

    private val IN_PROGRESS = ThreadLocal.withInitial { HashSet<Pair<PsiElement, Key<*>>>() }

    /**
     * Like [cached], but a computation that needs its own result (`val x = x.y`, cyclic imports)
     * gets [onCycle] instead of re-entering the cache (which the platform reports as an error).
     */
    fun <T> cachedNonRecursive(e: PsiElement, key: Key<CachedValue<T>>, oracle: LibraryOracle, onCycle: T, compute: () -> T): T {
        val marker = e to key
        val inProgress = IN_PROGRESS.get()
        if (marker in inProgress) return onCycle
        inProgress += marker
        try {
            return cached(e, key, oracle, compute)
        } finally {
            inProgress -= marker
        }
    }

    fun dependencies(e: PsiElement, oracle: LibraryOracle): Array<Any> {
        val roots = ProjectRootModificationTracker.getInstance(e.project)
        val own = oracle.tracker(e)
        return if (oracle.isLibrary(e)) arrayOf(roots, own) else arrayOf(PsiModificationTracker.MODIFICATION_COUNT, roots, own)
    }
}

/** Depth guard shared by recursive resolvers (cyclic imports, re-export loops). */
class RecursionGuard(private val maxDepth: Int = 64) {
    private val depth = ThreadLocal.withInitial { intArrayOf(0) }

    inline fun <T> run(empty: T, block: () -> T): T {
        if (!enter()) return empty
        try {
            return block()
        } finally {
            exit()
        }
    }

    fun enter(): Boolean {
        val d = depth.get()
        if (d[0] >= maxDepth) return false
        d[0]++
        return true
    }

    fun exit() {
        depth.get()[0]--
    }
}
