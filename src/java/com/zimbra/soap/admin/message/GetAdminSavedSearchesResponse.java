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

package com.zimbra.soap.admin.message;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.util.Collections;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import com.zimbra.common.soap.AdminConstants;
import com.zimbra.soap.type.NamedValue;

@XmlAccessorType(XmlAccessType.NONE)
@XmlRootElement(name=AdminConstants.E_GET_ADMIN_SAVED_SEARCHES_RESPONSE)
public class GetAdminSavedSearchesResponse {

    /**
     * @zm-api-field-description Information on saved searches
     */
    @XmlElement(name=AdminConstants.E_SEARCH, required=false)
    private List<NamedValue> searches = Lists.newArrayList();

    public GetAdminSavedSearchesResponse() {
    }

    public void setSearches(Iterable <NamedValue> searches) {
        this.searches.clear();
        if (searches != null) {
            Iterables.addAll(this.searches,searches);
        }
    }

    public GetAdminSavedSearchesResponse addSearch(NamedValue search) {
        this.searches.add(search);
        return this;
    }

    public List<NamedValue> getSearches() {
        return Collections.unmodifiableList(searches);
    }
}
