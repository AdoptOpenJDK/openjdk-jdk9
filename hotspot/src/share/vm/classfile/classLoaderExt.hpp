/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_VM_CLASSFILE_CLASSLOADEREXT_HPP
#define SHARE_VM_CLASSFILE_CLASSLOADEREXT_HPP

#include "classfile/classLoader.hpp"
#include "oops/instanceKlass.hpp"
#include "runtime/handles.hpp"

class ClassListParser;

class ClassLoaderExt: public ClassLoader { // AllStatic
public:

  class Context {
    const char* _file_name;
  public:
    Context(const char* class_name, const char* file_name, TRAPS) {
      _file_name = file_name;
    }

    bool check(const ClassFileStream* stream, const int classpath_index) {
      return true;
    }

    bool should_verify(int classpath_index) {
      return false;
    }

    instanceKlassHandle record_result(Symbol* class_name,
                                      ClassPathEntry* e,
                                      const s2 classpath_index,
                                      instanceKlassHandle result, TRAPS) {
      if (ClassLoader::add_package(_file_name, classpath_index, THREAD)) {
#if INCLUDE_CDS
        if (DumpSharedSpaces) {
          s2 classloader_type = ClassLoader::classloader_type(
                          class_name, e, classpath_index, CHECK_(result));
          result->set_shared_classpath_index(classpath_index);
          result->set_class_loader_type(classloader_type);
        }
#endif
        return result;
      } else {
        return instanceKlassHandle(); // NULL
      }
    }
  };


  static void add_class_path_entry(const char* path, bool check_for_duplicates,
                                   ClassPathEntry* new_entry) {
    ClassLoader::add_to_list(new_entry);
  }
  static void append_boot_classpath(ClassPathEntry* new_entry) {
    ClassLoader::add_to_list(new_entry);
  }
  static void setup_search_paths() {}
  static bool is_boot_classpath(int classpath_index) {
   return true;
 }
  static Klass* load_one_class(ClassListParser* parser, TRAPS);
};

#endif // SHARE_VM_CLASSFILE_CLASSLOADEREXT_HPP
