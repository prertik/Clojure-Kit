/*
 * Copyright 2016-present Greg Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.intellij.clojure.util

import com.intellij.lang.*
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Conditions
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.source.tree.TreeUtil
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.JBIterable
import org.intellij.clojure.lang.ClojureLanguage
import org.intellij.clojure.lang.ClojureTokens
import org.intellij.clojure.psi.*
import org.intellij.clojure.psi.impl.CComposite
import kotlin.reflect.KClass

/**
 * @author gregsh
 */

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> Any?.forceCast(): T? = this as? T

fun <E> E.isIn(c: Collection<E>) = c.contains(this)
fun String?.prefixedBy(c: Iterable<String>) = this != null && c.find { this.startsWith(it + ".") } != null
fun <E> Array<E>?.iterate() = this.jbIt()
fun <E: Any> E?.asListOrEmpty() = listOfNotNull(this)
fun <T> Iterable<T>?.jbIt() = JBIterable.from(this)
fun <T> Array<T>?.jbIt() = if (this == null) JBIterable.empty() else JBIterable.of(*this)

fun PsiElement?.isAncestorOf(o: PsiElement) = PsiTreeUtil.isAncestor(this, o, false)
fun <T : PsiElement> PsiElement?.findParent(c: KClass<T>) = PsiTreeUtil.getParentOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findChild(c: KClass<T>) = PsiTreeUtil.getChildOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findNext(c: KClass<T>) = PsiTreeUtil.getNextSiblingOfType(this, c.java)
fun <T : PsiElement> PsiElement?.findPrev(c: KClass<T>) = PsiTreeUtil.getPrevSiblingOfType(this, c.java)

val PsiElement?.role : Role get() = (this as? CElement)?.role ?: Role.NONE
val PsiElement?.asDef : CList? get() = if (role == Role.DEF) this as? CList else null

val PsiElement?.elementType : IElementType? get() = this?.node?.elementType
val PsiElement?.deepFirst: PsiElement? get() = if (this == null) null else PsiTreeUtil.getDeepestFirst(this)
val PsiElement?.deepLast: PsiElement? get() = if (this == null) null else PsiTreeUtil.getDeepestLast(this)
val PsiElement?.firstForm: CForm? get() = findChild(CForm::class)
val PsiElement?.nextForm: CForm? get() = findNext(CForm::class)
val PsiElement?.prevForm: CForm? get() = findPrev(CForm::class)
val PsiElement?.thisForm: CForm? get() = (this as? CForm ?: findParent(CForm::class)).let {
  ((it as? CSymbol)?.parent as? CSymbol ?: it).let { it?.parent as? CSForm ?: it } }
val PsiElement?.parentForm: CForm? get() = thisForm.findParent(CForm::class)
val PsiElement?.parentForms: JBIterable<CForm> get() = JBIterable.generate(this.parentForm, { it.parentForm })
val PsiElement?.childForms: JBIterable<CForm> get() = iterate(CForm::class)
fun IElementType?.wsOrComment() = this != null && (ClojureTokens.WHITESPACES.contains(this) || ClojureTokens.COMMENTS.contains(this))

fun PsiElement?.findChild(role: Role) = iterate().find { (it as? CElement)?.role == role } as CForm?
fun PsiElement?.findChild(c: IElementType) = this?.node?.findChildByType(c)?.psi
fun PsiElement?.findNext(c: IElementType) = TreeUtil.findSibling(this?.node, c)?.psi
fun PsiElement?.findPrev(c: IElementType) = TreeUtil.findSiblingBackward(this?.node, c)?.psi

val IDef.qualifiedName: String
  get() = name.withNamespace(namespace)
fun String.withNamespace(namespace: String) = if (namespace.isEmpty()) this else "$namespace/$this"
fun String.withPackage(packageName: String) = if (packageName.isEmpty()) this else "$packageName.$this"

fun VirtualFile.toIoFile() = VfsUtil.virtualToIoFile(this)

@Suppress("UNCHECKED_CAST")
fun <T> JBIterable<T?>.notNulls(): JBIterable<T> = filter { it != null } as JBIterable<T>
fun <T: Any, E: Any> JBIterable<T>.filter(c : KClass<E>) = filter(c.java)

fun ASTNode?.iterate(): JBIterable<ASTNode> =
    if (this == null) JBIterable.empty() else cljNodeTraverser().expandAndSkip(Conditions.equalTo(this)).traverse()

fun CComposite?.iterate(): JBIterable<PsiElement> = (this as PsiElement?).iterate()

fun PsiElement?.iterate(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty()
    else if (this is CFile) cljTraverser().expandAndSkip(Conditions.equalTo(this)).traverse()
    else firstChild?.siblings() ?: JBIterable.empty()
fun <E> SyntaxTraverser<E>.iterate(e: E) = withRoot(e).expandAndSkip(Conditions.equalTo(e)).traverse()
fun <T> Iterator<T>.safeNext(): T? = if (hasNext()) next() else null

fun <T: Any> PsiElement?.iterate(c: KClass<T>): JBIterable<T> = iterate().filter(c)

fun PsiElement?.iterateRCAware(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else cljTraverserRCAware().expandAndSkip(Conditions.equalTo(this)).traverse()

fun PsiElement?.siblings(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else JBIterable.generate(this, { it.nextSibling }).notNulls()

fun PsiElement?.prevSiblings(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else JBIterable.generate(this, { it.prevSibling }).notNulls()

fun PsiElement?.parents(): JBIterable<PsiElement> =
    if (this == null) JBIterable.empty() else SyntaxTraverser.psiApi().parents(this)

fun _cljTraverser(): SyntaxTraverser<PsiElement> = SyntaxTraverser.psiTraverser()
    .forceDisregardTypes { it == GeneratedParserUtilBase.DUMMY_BLOCK }

fun _cljNodeTraverser(): SyntaxTraverser<ASTNode> = SyntaxTraverser.astTraverser()
    .forceDisregardTypes { it == GeneratedParserUtilBase.DUMMY_BLOCK }

fun cljLightTraverser(text: CharSequence,
                      language: Language = ClojureLanguage,
                      forcedRootType: IElementType? = null): SyntaxTraverser<LighterASTNode> {
  val builder = parseTextLight(language, text, forcedRootType)
  return SyntaxTraverser.lightTraverser(builder).forceDisregardTypes { it == GeneratedParserUtilBase.DUMMY_BLOCK }
}

private fun parseTextLight(language: Language, text: CharSequence, forcedRootType: IElementType?): PsiBuilder {
  val parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(language)
  val lexer = parserDefinition.createLexer(null)
  val parser = parserDefinition.createParser(null) as LightPsiParser
  val builder = PsiBuilderFactory.getInstance().createBuilder(parserDefinition, lexer, text)
  parser.parseLight(forcedRootType ?: parserDefinition.fileNodeType, builder)
  return builder
}

fun PsiElement?.cljTraverser(): SyntaxTraverser<PsiElement> = org.intellij.clojure.util._cljTraverser().withRoot(this)
fun PsiElement?.cljTraverserRCAware(): SyntaxTraverser<PsiElement> = cljTraverser().forceDisregard { e ->
  (e as? CElement)?.role.let { r ->
    r == Role.RCOND || r == Role.RCOND_S || e.parent.role.let { pr ->
      pr == Role.RCOND_S && e.prevForm is CKeyword }
  }
}.forceIgnore { e -> e.parentForm?.role == Role.RCOND && e.prevForm !is CKeyword }

fun ASTNode?.cljNodeTraverser(): SyntaxTraverser<ASTNode> = org.intellij.clojure.util._cljNodeTraverser().withRoot(this)

fun PsiElement?.formPrefix(): JBIterable<CElement> = iterate()
    .takeWhile { it is CMetadata || it is CReaderMacro || it.elementType.wsOrComment() }
    .filter(CElement::class)

val PsiElement.valueRange: TextRange get() = firstChild.siblings()
      .skipWhile { it is CReaderMacro || it is CMetadata || (it !is CToken && it !is CForm) }
      .first()?.textRange?.let { TextRange(it.startOffset, textRange.endOffset) } ?: textRange

class EachNth(private val each: Int) : JBIterable.Stateful<EachNth>(), Condition<Any?> {
  private var idx = -1
  override fun value(t: Any?) = run { idx = ++ idx % each; idx == 0 }
}
