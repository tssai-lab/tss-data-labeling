// Adapter boundary for dependencies owned by the surrounding LS application.
// ConfigPage, template browsing and configuration widgets remain upstream code.
import React, {createContext,forwardRef,useContext} from 'react';
export const PlatformConfigContext=createContext(null);
export const useAPI=()=>useContext(PlatformConfigContext).api;
export const Input=forwardRef((props,ref)=><input {...props} ref={ref}/>);
export const Form={Actions:({children,extra})=><footer className="platform-config-actions">{extra}{children}</footer>};
export const Spinner=({style})=><div role="status" style={style}>正在加载…</div>;
// Route-level leave handling belongs to the platform router, not LS's router.
export const UnsavedChanges=()=>null;
