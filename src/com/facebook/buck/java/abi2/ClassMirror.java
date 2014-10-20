/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.java.abi2;

import com.google.common.collect.Sets;
import com.google.common.io.ByteSource;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.SortedSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.annotation.Nullable;

class ClassMirror extends ClassVisitor implements Comparable<ClassMirror> {

  private final String fileName;
  private final SortedSet<AnnotationMirror> annotations;
  private final SortedSet<FieldMirror> fields;
  private final SortedSet<MethodMirror> methods;
  private int version;
  private int access;
  @Nullable
  private String signature;
  @Nullable
  private String[] interfaces;
  @Nullable
  private String superName;
  @Nullable
  private String name;

  public ClassMirror(String name) {
    super(Opcodes.ASM5);

    this.fileName = name;
    this.annotations = Sets.newTreeSet();
    this.fields = Sets.newTreeSet();
    this.methods = Sets.newTreeSet();
  }

  @Override
  public void visit(
      int version,
      int access,
      String name,
      String signature,
      String superName,
      String[] interfaces) {
    this.name = name;
    this.version = version;
    this.access = access;
    this.signature = signature;
    this.interfaces = interfaces;
    this.superName = superName;
  }

  @Override
  public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
    AnnotationMirror mirror = new AnnotationMirror(desc, visible);
    annotations.add(mirror);
    return mirror;
  }

  @Override
  public FieldVisitor visitField(
      int access,
      String name,
      String desc,
      String signature,
      Object value) {
    if ((access & Opcodes.ACC_PRIVATE) > 0) {
      return super.visitField(access, name, desc, signature, value);
    }

    FieldMirror mirror = new FieldMirror(access, name, desc, signature, value);
    fields.add(mirror);
    return mirror;
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {

    // Bridge methods are created by the compiler, and don't appear in source. Skip them. Synthetic
    // methods are also generated by the compiler, unless it's one of the methods named in section
    // 4.7.8 of the JVM spec, which are "<init>" and "Enum.valueOf()" and "Enum.values". None of
    // these are actually harmful to the ABI, so we allow synthetic methods through. Bridge ones?
    // They can go to heck.
    // http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.7.8

    if ((access & Opcodes.ACC_PRIVATE) > 0 || (access & Opcodes.ACC_BRIDGE) > 0) {
      return super.visitMethod(access, name, desc, signature, exceptions);
    }
    MethodMirror mirror = new MethodMirror(access, name, desc, signature, exceptions);
    methods.add(mirror);

    return mirror;
  }

  @Override
  public int compareTo(ClassMirror o) {
    return fileName.compareTo(o.fileName);
  }

  public void writeTo(JarOutputStream jar) throws IOException {
    JarEntry entry = new JarEntry(fileName);
    entry.setTime(0);

    jar.putNextEntry(entry);
    ClassWriter writer = new ClassWriter(0);
    writer.visit(version, access, name, signature, superName, interfaces);

    for (AnnotationMirror annotation : annotations) {
      annotation.appendTo(writer);
    }

    for (FieldMirror field : fields) {
      field.accept(writer);
    }

    for (MethodMirror method : methods) {
      method.appendTo(writer);
    }
    writer.visitEnd();
    ByteSource.wrap(writer.toByteArray()).copyTo(jar);
    jar.closeEntry();
  }
}