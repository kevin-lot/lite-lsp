// Derived from intellij-community python-parser (Apache 2.0, see python/THIRD_PARTY_LICENSE-intellij-community.txt).
/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
 */
package me.kevinlot.lite.python.upstream.parsing.console;

import me.kevinlot.lite.python.upstream.psi.PyElementType;

public final class PyConsoleTokenTypes {
  public static final PyElementType QUESTION_MARK = new PyElementType("QUESTION_MARK"); //?
  public static final PyElementType PLING = new PyElementType("PLING"); //!
  public static final PyElementType DOLLAR = new PyElementType("DOLLAR"); //$

  public static final PyElementType SHELL_COMMAND = new PyElementType("SHELL_COMMAND");
  public static final PyElementType MAGIC_COMMAND_LINE = new PyElementType("MAGIC_COMMAND_LINE");

  private PyConsoleTokenTypes() {
  }
}
