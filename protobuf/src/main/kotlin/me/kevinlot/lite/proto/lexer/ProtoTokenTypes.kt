package me.kevinlot.lite.proto.lexer

import com.intellij.psi.tree.IElementType
import me.kevinlot.lite.proto.PbLanguage

/** Token classes the imported lexer and token table use (see src/upstream). */
open class ProtoTokenType(debugName: String) : IElementType(debugName, PbLanguage)

/** `message`, `enum`…: keywords that may also be names (`message message {}` is valid protobuf). */
class ProtoKeywordTokenType(debugName: String) : ProtoTokenType(debugName)

class ProtoCommentTokenType(debugName: String) : ProtoTokenType(debugName)
