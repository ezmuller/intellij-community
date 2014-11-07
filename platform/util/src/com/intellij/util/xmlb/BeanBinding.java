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
package com.intellij.util.xmlb;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ReflectionUtil;
import com.intellij.util.containers.ConcurrentSoftValueHashMap;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.ContainerUtilRt;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.xmlb.annotations.*;
import gnu.trove.TObjectDoubleHashMap;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.List;

class BeanBinding extends Binding {
  private static final Logger LOG = Logger.getInstance(BeanBinding.class);

  private static final Map<Class, List<Accessor>> ourAccessorCache = new ConcurrentSoftValueHashMap<Class, List<Accessor>>();

  private final String myTagName;
  @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
  private Binding[] myBindings;

  private final Class<?> myBeanClass;

  public BeanBinding(@NotNull Class<?> beanClass, @Nullable Accessor accessor) {
    super(accessor);

    assert !beanClass.isArray() : "Bean is an array: " + beanClass;
    assert !beanClass.isPrimitive() : "Bean is primitive type: " + beanClass;
    myBeanClass = beanClass;
    myTagName = getTagName(beanClass);
    assert !StringUtil.isEmptyOrSpaces(myTagName) : "Bean name is empty: " + beanClass;
  }

  @Override
  public synchronized void init() {
    assert myBindings == null;

    List<Accessor> accessors = getAccessors(myBeanClass);
    myBindings = new Binding[accessors.size()];
    for (int i = 0, size = accessors.size(); i < size; i++) {
      Binding binding = createBinding(accessors.get(i));
      binding.init();
      myBindings[i] = binding;
    }
  }

  @Override
  @Nullable
  public Object serialize(@NotNull Object o, @Nullable Object context, @NotNull SerializationFilter filter) {
    return serializeInto(o, context == null ? null : new Element(myTagName), filter);
  }

  public Element serialize(@NotNull Object object, boolean createElementIfEmpty, @NotNull SerializationFilter filter) {
    return serializeInto(object, createElementIfEmpty ? new Element(myTagName) : null, filter);
  }

  @Nullable
  public Element serializeInto(@NotNull Object o, @Nullable Element element, @NotNull SerializationFilter filter) {
    return serializeInto(o, element, filter, myBindings);
  }

  @Nullable
  public Element serializeInto(@NotNull Object o, @Nullable Element element, @NotNull SerializationFilter filter, @Nullable Binding[] bindings) {
    for (Binding binding : bindings == null ? myBindings : bindings) {
      Accessor accessor = binding.getAccessor();
      if (!filter.accepts(accessor, o)) {
        continue;
      }

      //todo: optimize. Cache it.
      Property property = accessor.getAnnotation(Property.class);
      if (property != null && property.filter() != SerializationFilter.class &&
          !ReflectionUtil.newInstance(property.filter()).accepts(accessor, o)) {
        continue;
      }

      if (element == null) {
        element = new Element(myTagName);
      }

      Object node = binding.serialize(o, element, filter);
      if (node != null) {
        if (node instanceof org.jdom.Attribute) {
          element.setAttribute((org.jdom.Attribute)node);
        }
        else {
          JDOMUtil.addContent(element, node);
        }
      }
    }
    return element;
  }

  @Override
  public Object deserialize(Object o, @NotNull Object... nodes) {
    Element element = null;
    for (Object aNode : nodes) {
      if (!XmlSerializerImpl.isIgnoredNode(aNode)) {
        element = (Element)aNode;
        break;
      }
    }

    if (element == null) {
      return o;
    }
    Object instance = ReflectionUtil.newInstance(myBeanClass);
    deserializeInto(instance, element, null);
    return instance;
  }

  @NotNull
  public Binding[] computeOrderedBindings(@NotNull LinkedHashSet<String> accessorNameTracker) {
    final TObjectDoubleHashMap<String> weights = new TObjectDoubleHashMap<String>(accessorNameTracker.size());
    double weight = 0;
    double step = (double)myBindings.length / (double)accessorNameTracker.size();
    for (String name : accessorNameTracker) {
      weights.put(name, weight);
      weight += step;
    }

    weight = 0;
    for (Binding binding : myBindings) {
      String name = binding.getAccessor().getName();
      if (!weights.containsKey(name)) {
        weights.put(name, weight);
      }

      weight++;
    }

    Binding[] result = Arrays.copyOf(myBindings, myBindings.length);
    Arrays.sort(result, new Comparator<Binding>() {
      @Override
      public int compare(@NotNull Binding o1, @NotNull Binding o2) {
        String n1 = o1.getAccessor().getName();
        String n2 = o2.getAccessor().getName();
        double w1 = weights.get(n1);
        double w2 = weights.get(n2);
        return (int)(w1 - w2);
      }
    });
    return result;
  }

  public void deserializeInto(@NotNull Object result, @NotNull Element element, @Nullable Set<String> accessorNameTracker) {
    MultiMap<Binding, Object> data = MultiMap.createLinked();
    nextNode:
    for (Object child : ContainerUtil.concat(element.getContent(), element.getAttributes())) {
      if (XmlSerializerImpl.isIgnoredNode(child)) {
        continue;
      }

      for (Binding binding : myBindings) {
        if (binding.isBoundTo(child)) {
          data.putValue(binding, child);
          continue nextNode;
        }
      }

      final String message = "Format error: no binding for " + child + " inside " + this;
      LOG.debug(message);
      Logger.getInstance(myBeanClass.getName()).debug(message);
      Logger.getInstance("#" + myBeanClass.getName()).debug(message);
    }

    for (Binding binding : data.keySet()) {
      if (accessorNameTracker != null) {
        accessorNameTracker.add(binding.getAccessor().getName());
      }
      binding.deserialize(result, ArrayUtil.toObjectArray(data.get(binding)));
    }
  }

  @Override
  public boolean isBoundTo(Object node) {
    return node instanceof Element && ((Element)node).getName().equals(myTagName);
  }

  @Override
  public Class getBoundNodeType() {
    return Element.class;
  }

  private static String getTagName(Class<?> aClass) {
    for (Class<?> c = aClass; c != null; c = c.getSuperclass()) {
      String name = getTagNameFromAnnotation(c);
      if (name != null) {
        return name;
      }
    }
    return aClass.getSimpleName();
  }

  private static String getTagNameFromAnnotation(Class<?> aClass) {
    Tag tag = aClass.getAnnotation(Tag.class);
    if (tag != null && !tag.value().isEmpty()) return tag.value();
    return null;
  }

  @NotNull
  static List<Accessor> getAccessors(Class<?> aClass) {
    List<Accessor> accessors = ourAccessorCache.get(aClass);
    if (accessors != null) {
      return accessors;
    }

    accessors = ContainerUtil.newArrayList();

    if (aClass != Rectangle.class) {   // special case for Rectangle.class to avoid infinite recursion during serialization due to bounds() method
      collectPropertyAccessors(aClass, accessors);
    }
    collectFieldAccessors(aClass, accessors);

    ourAccessorCache.put(aClass, accessors);

    return accessors;
  }

  private static void collectPropertyAccessors(Class<?> aClass, List<Accessor> accessors) {
    final Map<String, Couple<Method>> candidates = ContainerUtilRt.newTreeMap(); // (name,(getter,setter))
    for (Method method : aClass.getMethods()) {
      if (!Modifier.isPublic(method.getModifiers())) {
        continue;
      }

      Pair<String, Boolean> propertyData = getPropertyData(method.getName()); // (name,isSetter)
      if (propertyData == null || propertyData.first.equals("class") ||
          method.getParameterTypes().length != (propertyData.second ? 1 : 0)) {
        continue;
      }

      Couple<Method> candidate = candidates.get(propertyData.first);
      if (candidate == null) {
        candidate = Couple.getEmpty();
      }
      if ((propertyData.second ? candidate.second : candidate.first) != null) {
        continue;
      }
      candidate = Couple.of(propertyData.second ? candidate.first : method, propertyData.second ? method : candidate.second);
      candidates.put(propertyData.first, candidate);
    }
    for (Map.Entry<String, Couple<Method>> candidate: candidates.entrySet()) {
      Couple<Method> methods = candidate.getValue(); // (getter,setter)
      if (methods.first != null && methods.second != null &&
          methods.first.getReturnType().equals(methods.second.getParameterTypes()[0]) &&
          methods.first.getAnnotation(Transient.class) == null &&
          methods.second.getAnnotation(Transient.class) == null) {
        accessors.add(new PropertyAccessor(candidate.getKey(), methods.first.getReturnType(), methods.first, methods.second));
      }
    }
  }

  private static void collectFieldAccessors(@NotNull Class<?> aClass, @NotNull List<Accessor> accessors) {
    Class<?> currentClass = aClass;
    do {
      for (Field field : currentClass.getDeclaredFields()) {
        int modifiers = field.getModifiers();
        if (!Modifier.isStatic(modifiers) &&
            (field.getAnnotation(OptionTag.class) != null ||
             field.getAnnotation(Tag.class) != null ||
             field.getAnnotation(Attribute.class) != null ||
             field.getAnnotation(Property.class) != null ||
             (Modifier.isPublic(modifiers) &&
              !Modifier.isFinal(modifiers) &&
              !Modifier.isTransient(modifiers) &&
              field.getAnnotation(Transient.class) == null))) {
          accessors.add(new FieldAccessor(field));
        }
      }
    }
    while ((currentClass = currentClass.getSuperclass()) != null && currentClass.getAnnotation(Transient.class) == null);
  }

  @Nullable
  private static Pair<String, Boolean> getPropertyData(@NotNull String methodName) {
    String part = "";
    boolean isSetter = false;
    if (methodName.startsWith("get")) {
      part = methodName.substring(3, methodName.length());
    }
    else if (methodName.startsWith("is")) {
      part = methodName.substring(2, methodName.length());
    }
    else if (methodName.startsWith("set")) {
      part = methodName.substring(3, methodName.length());
      isSetter = true;
    }
    return part.isEmpty() ? null : Pair.create(Introspector.decapitalize(part), isSetter);
  }

  public String toString() {
    return "BeanBinding[" + myBeanClass.getName() + ", tagName=" + myTagName + "]";
  }

  @NotNull
  private static Binding createBinding(@NotNull Accessor accessor) {
    Binding binding = XmlSerializerImpl.getTypeBinding(accessor.getGenericType(), accessor);
    if (binding instanceof JDOMElementBinding) {
      return binding;
    }

    Attribute attribute = accessor.getAnnotation(Attribute.class);
    if (attribute != null) {
      return new AttributeBinding(accessor, attribute);
    }

    Tag tag = accessor.getAnnotation(Tag.class);
    if (tag != null && !tag.value().isEmpty()) {
      return new TagBinding(accessor, tag);
    }

    Text text = accessor.getAnnotation(Text.class);
    if (text != null) {
      return new TextBinding(accessor);
    }

    boolean surroundWithTag = true;
    Property property = accessor.getAnnotation(Property.class);
    if (property != null) {
      surroundWithTag = property.surroundWithTag();
    }

    if (!surroundWithTag) {
      if (!Element.class.isAssignableFrom(binding.getBoundNodeType())) {
        throw new XmlSerializationException("Text-serializable properties can't be serialized without surrounding tags: " + accessor);
      }
      return new AccessorBindingWrapper(accessor, binding);
    }

    return new OptionTagBinding(accessor, accessor.getAnnotation(OptionTag.class));
  }
}
