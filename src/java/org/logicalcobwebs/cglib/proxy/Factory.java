/*
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2002 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Apache" and "Apache Software Foundation" must
 *    not be used to endorse or promote products derived from this
 *    software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    nor may "Apache" appear in their name, without prior written
 *    permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package org.logicalcobwebs.cglib.proxy;

/**
 * All enhanced instances returned by the {@link Enhancer} class implement this interface.
 * Using this interface for new instances is faster than going through the <code>Enhancer</code>
 * interface or using reflection. In addition, to intercept methods called during
 * object construction you <b>must</b> use these methods instead of reflection.
 * @author Juozas Baliuka <a href="mailto:baliuka@mwm.lt">baliuka@mwm.lt</a>
 * @version $Id: Factory.java,v 1.1 2003/12/12 19:28:11 billhorsman Exp $
 */
public interface Factory {
    /**
     * Creates new instance of the same type, using the no-arg
     * contructor, and copying the callbacks from the existing instance.
     * @return new instance of the same type
     */     
    Object newInstance();

    /**
     * Creates new instance of the same type, using the no-arg constructor.
     * The class of this object must have been created using a single Callback type.
     * If multiple callbacks are required an exception will be thrown.
     * @param callback the new interceptor to use
     * @return new instance of the same type
     */     
    Object newInstance(Callback callback);
    
    /**
     * Creates new instance of the same type, using the no-arg constructor.
     * @param callbacks the new callbacks(s) to use
     * @return new instance of the same type
     */     
    Object newInstance(Callback[] callbacks);

    /**
     * Creates a new instance of the same type, using the constructor
     * matching the given signature, and copying the callbacks
     * from the existing instance.
     * @param types the constructor argument types
     * @param args the constructor arguments
     * @return new instance of the same type
     */     
    Object newInstance(Class[] type, Object[] args);

    /**
     * Creates a new instance of the same type, using the constructor
     * matching the given signature.
     * @param types the constructor argument types
     * @param args the constructor arguments
     * @param callbacks the new interceptor(s) to use
     * @return new instance of the same type
     */
    Object newInstance(Class[] types, Object[] args, Callback[] callbacks);

    /**
     * Return the <code>Callback</code> implementation at the specified index.
     * @param index the callback index
     * @return the callback implementation
     */
    Callback getCallback(int index);

    /**
     * Set the callback for this object for the given type.
     * @param index the callback index to replace
     * @param callback the new callback
     */
    void setCallback(int index, Callback callback);

    /**
     * Replace all of the callbacks for this object at once.
     * @param callbacks the new callbacks(s) to use
     */
    void setCallbacks(Callback[] callbacks);
}