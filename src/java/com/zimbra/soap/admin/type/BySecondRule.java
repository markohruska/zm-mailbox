/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2011, 2012, 2013 Zimbra Software, LLC.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.4 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.soap.admin.type;

import com.google.common.base.Objects;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;

import com.zimbra.common.soap.MailConstants;
import com.zimbra.soap.base.BySecondRuleInterface;

@XmlAccessorType(XmlAccessType.NONE)
public class BySecondRule implements BySecondRuleInterface {

    /**
     * @zm-api-field-tag second-list
     * @zm-api-field-description Comma separated list of seconds where second is a number between 0 and 59
     */
    @XmlAttribute(name=MailConstants.A_CAL_RULE_BYSECOND_SECLIST /* seclist */, required=true)
    private final String list;

    /**
     * no-argument constructor wanted by JAXB
     */
    @SuppressWarnings("unused")
    private BySecondRule() {
        this((String) null);
    }

    public BySecondRule(String list) {
        this.list = list;
    }

    @Override
    public BySecondRuleInterface create(String list) {
        return new BySecondRule(list);
    }

    @Override
    public String getList() { return list; }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
            .add("list", list)
            .toString();
    }
}
