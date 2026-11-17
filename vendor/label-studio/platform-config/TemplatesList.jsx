import React from "react";
import {translate} from './locale';
import { Spinner } from "./Bridge";
import { useAPI } from "./Bridge";
import { cn } from "../libs/editor/src/utils/bem";
import "./Config.scss";
import { Button, EnterpriseBadge } from "@humansignal/ui";

const listClass = cn("templates-list");

const Arrow = () => (
  <svg width="8" height="12" viewBox="0 0 8 12" fill="none" xmlns="http://www.w3.org/2000/svg">
    <title>分类箭头</title>
    <path opacity="0.9" d="M2 10L6 6L2 2" stroke="currentColor" strokeWidth="2" strokeLinecap="square" />
  </svg>
);

const TemplatesInGroup = ({ templates, group, onSelectRecipe, isEdition }) => {
  const picked = templates
    .filter((recipe) => recipe.group === group)
    // templates without `order` go to the end of the list
    .sort((a, b) => (a.order ?? Number.POSITIVE_INFINITY) - (b.order ?? Number.POSITIVE_INFINITY));

  const isCommunityEdition = isEdition === "Community";

  return (
    <ul>
      {picked.map((recipe) => {
        const isEnterpriseTemplate = recipe.type === "enterprise";
        const isDisabled = isCommunityEdition && isEnterpriseTemplate;

        return (
          <li
            key={recipe.id || recipe.title}
            onClick={() => !isDisabled && onSelectRecipe(recipe)}
            className={listClass.elem("template").mod({ disabled: isDisabled }).toClassName()}
            title={isDisabled ? "企业版模板：当前源码版本不提供此能力" : ""}
          >
            <img src={recipe.image} alt={""} />
            <div className="flex flex-col items-center w-full">
              <h3 className="flex flex-1 justify-center text-center w-full">{translate(recipe.title)}</h3>
              {isEnterpriseTemplate && isCommunityEdition && <EnterpriseBadge className="mb-base" />}
            </div>
          </li>
        );
      })}
    </ul>
  );
};

export const TemplatesList = ({ selectedGroup, selectedRecipe, onCustomTemplate, onSelectGroup, onSelectRecipe }) => {
  const [groups, setGroups] = React.useState([]);
  const [templates, setTemplates] = React.useState();
  const [failure,setFailure]=React.useState(''),[reload,setReload]=React.useState(0);
  const api = useAPI();
  const isEdition = "Community";

  React.useEffect(() => {
    const fetchData = async () => {
      setFailure('');
      const res = await api.callApi("configTemplates");

      if (!res) return;
      if(res.error){setTemplates([]);setFailure(res.response?.validation_errors?.join('；')||'模板读取失败');return;}
      const { templates, groups } = res;

      setTemplates(templates);
      setGroups(groups);
    };
    fetchData();
  }, [reload]);

  const selected = selectedGroup || groups[0];

  return (
    <div className={listClass}>
      <aside className={listClass.elem("sidebar").toClassName()}>
        <ul>
          {groups.map((group) => (
            <li
              key={group}
              onClick={() => onSelectGroup(group)}
              className={listClass
                .elem("group")
                .mod({
                  active: selected === group,
                  selected: selectedRecipe?.group === group,
                })
                .toClassName()}
            >
              {translate(group)}
              <Arrow />
            </li>
          ))}
        </ul>
        <Button
          type="button"
          align="left"
          look="string"
          size="small"
          onClick={onCustomTemplate}
          className="w-full"
          aria-label="创建自定义配置"
        >
          自定义配置
        </Button>
      </aside>
      <main>
        {failure&&<div role="alert"><p>{failure}</p><Button onClick={()=>setReload(value=>value+1)}>重新加载模板</Button></div>}
        {!templates && <Spinner style={{ width: "100%", height: 200 }} />}
        <TemplatesInGroup
          templates={templates || []}
          group={selected}
          onSelectRecipe={onSelectRecipe}
          isEdition={isEdition}
        />
      </main>
    </div>
  );
};
