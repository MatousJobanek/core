/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.ejb;

import javax.ejb.TransactionManagement;
import javax.ejb.TransactionManagementType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.transaction.UserTransaction;

import org.jboss.weld.bean.SessionBean;
import org.jboss.weld.literal.DefaultLiteral;
import org.jboss.weld.logging.ValidatorLogger;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.module.PlugableValidator;
import org.jboss.weld.util.reflection.Formats;

class WeldEjbValidator implements PlugableValidator {

    public void validateInjectionPointForDefinitionErrors(InjectionPoint ij, Bean<?> bean, BeanManagerImpl beanManager) {
        // check that UserTransaction is not injected into a SessionBean with container-managed transactions
        if (bean instanceof SessionBean<?>) {
            SessionBean<?> sessionBean = (SessionBean<?>) bean;
            if (UserTransaction.class.equals(ij.getType()) && (ij.getQualifiers().isEmpty() || ij.getQualifiers().contains(DefaultLiteral.INSTANCE))
                    && hasContainerManagedTransactions(sessionBean)) {
                throw ValidatorLogger.LOG.userTransactionInjectionIntoBeanWithContainerManagedTransactions(ij, Formats.formatAsStackTraceElement(ij));
            }
        }
    }

    private boolean hasContainerManagedTransactions(SessionBean<?> bean) {
        TransactionManagement transactionManagementAnnotation = bean.getAnnotated().getAnnotation(TransactionManagement.class);
        if (transactionManagementAnnotation == null) {
            return true;
        }
        return transactionManagementAnnotation.value() == TransactionManagementType.CONTAINER;
    }
}
