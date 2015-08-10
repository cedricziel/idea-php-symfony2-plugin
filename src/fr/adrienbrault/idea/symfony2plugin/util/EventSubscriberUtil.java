package fr.adrienbrault.idea.symfony2plugin.util;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlTag;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.PhpClass;
import fr.adrienbrault.idea.symfony2plugin.config.EventDispatcherSubscriberUtil;
import fr.adrienbrault.idea.symfony2plugin.config.dic.EventDispatcherSubscribedEvent;
import fr.adrienbrault.idea.symfony2plugin.dic.tags.ServiceTagFactory;
import fr.adrienbrault.idea.symfony2plugin.dic.tags.ServiceTagInterface;
import fr.adrienbrault.idea.symfony2plugin.dic.tags.ServiceTagVisitorInterface;
import fr.adrienbrault.idea.symfony2plugin.stubs.ServiceIndexUtil;
import fr.adrienbrault.idea.symfony2plugin.util.dict.ServiceUtil;
import fr.adrienbrault.idea.symfony2plugin.util.yaml.YamlHelper;
import fr.adrienbrault.idea.symfony2plugin.util.yaml.visitor.YamlServiceTag;
import fr.adrienbrault.idea.symfony2plugin.util.yaml.visitor.YamlTagVisitor;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLKeyValue;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

/**
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class EventSubscriberUtil {

    public static void visitNamedTag(@NotNull Project project, @NotNull String tagName, @NotNull ServiceTagVisitorInterface visitor) {

        for (String service : ServiceUtil.getTaggedServices(project, tagName)) {
            for (PsiElement psiElement : ServiceIndexUtil.findServiceDefinitions(project, service)) {
                Collection<ServiceTagInterface> serviceTagVisitorArguments = ServiceTagFactory.create(service, psiElement);

                if(serviceTagVisitorArguments == null) {
                    continue;
                }

                for (ServiceTagInterface tagVisitorArgument : serviceTagVisitorArguments) {
                    if(tagName.equals(tagVisitorArgument.getName())) {
                        visitor.visit(tagVisitorArgument);
                    }
                }
            }
        }
    }

    @Nullable
    public static String getTaggedEventMethodParameter(Project project, String eventName) {

        // strong internal events
        if(ServiceUtil.TAGS.containsKey(eventName)) {
            return ServiceUtil.TAGS.get(eventName);
        }

        EventsCollector[] collectors = new EventsCollector[] {
            new XmlEventsCollector(),
            new YmlEventsCollector(),
        };

        for (String service : ServiceUtil.getTaggedServices(project, "kernel.event_listener")) {

            for (PsiElement psiElement : ServiceIndexUtil.findServiceDefinitions(project, service)) {

                Collection<String> methods = new HashSet<String>();

                for (EventsCollector collector : collectors) {
                    methods.addAll(collector.collect(psiElement, eventName));
                }

                // find a method
                PhpClass phpClass = ServiceUtil.getServiceClass(project, service);
                if(phpClass != null) {
                    for (String methodName : methods) {
                        Method method = phpClass.findMethodByName(methodName);
                        if(method != null) {
                            String methodParameterClassHint = PhpElementsUtil.getMethodParameterTypeHint(method);
                            if(methodParameterClassHint != null) {
                                return methodParameterClassHint;
                            }
                        }
                    }
                }

            }

        }

        for (EventDispatcherSubscribedEvent event : EventDispatcherSubscriberUtil.getSubscribedEvent(project, eventName)) {
            String methodName = event.getMethodName();
            if(methodName == null) {
                continue;
            }

            Method method = PhpElementsUtil.getClassMethod(project, event.getFqnClassName(), methodName);
            if(method != null) {
                String methodParameterClassHint = PhpElementsUtil.getMethodParameterTypeHint(method);
                if(methodParameterClassHint != null) {
                    return methodParameterClassHint;
                }
            }
        }

        return null;
    }

    public interface EventsCollector {
        Collection<String> collect(@NotNull PsiElement psiElement, @NotNull String eventName);
    }

    /**
     * TODO: implement abstract "TagVisitor"
     *
     * - { name: kernel.event_listener, event: eventName, method: methodName }
     */
    private static class YmlEventsCollector implements EventsCollector {

        public Collection<String> collect(@NotNull PsiElement psiElement, @NotNull String eventName) {

            if(!(psiElement instanceof YAMLKeyValue)) {
                return Collections.emptySet();
            }

            final Collection<String> methods = new HashSet<String>();

            YamlHelper.visitTagsOnServiceDefinition((YAMLKeyValue) psiElement, new YamlTagVisitor() {
                @Override
                public void visit(@NotNull YamlServiceTag args) {
                    if (!"kernel.event_listener".equals(args.getName())) {
                        return;
                    }

                    String methodName = args.getAttribute("method");
                    if (StringUtils.isBlank(methodName)) {
                        return;
                    }

                    methods.add(methodName);
                }

            });

            return methods;
        }

    }

    /**
     * TODO: implement abstract "TagVisitor"
     *
     * <tag name="kernel.event_listener" event="event_bar" method="foo" />
     */
    private static class XmlEventsCollector implements EventsCollector {

        public Collection<String> collect(@NotNull PsiElement psiElement, @NotNull String eventName) {

            if(!(psiElement instanceof XmlTag)) {
                return Collections.emptySet();
            }

            Collection<String> methods = new HashSet<String>();
            for (XmlTag tag : ((XmlTag) psiElement).findSubTags("tag")) {

                if(!"kernel.event_listener".equals(tag.getAttributeValue("name")) || !eventName.equals(tag.getAttributeValue("event"))) {
                    continue;
                }

                String methodName = tag.getAttributeValue("method");
                if(StringUtils.isBlank(methodName)) {
                    continue;
                }

                methods.add(methodName);
            }

            return methods;
        }

    }

}