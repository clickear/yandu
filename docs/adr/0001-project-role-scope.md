# 项目角色采用单角色、项目级授权

每名成员在一个项目中仅分配一个项目级角色，角色对项目内所有目标语言生效，不按语言细分。Owner、Admin、Translator、Reviewer、Viewer 职责分离，禁止用户审核自己最后编辑的版本；每个项目只有一名 Owner。Admin 可管理除 Owner/Admin 以外的成员，只有 Owner 能授予或撤销 Admin、转让所有权；Owner 将所有权转交给现有 Admin 后自动变为 Admin。非 Owner 可自行退出，Owner 必须先转让所有权。此模型优先满足当前团队工作流并保持授权规则可理解，语言级权限或可组合角色需在出现真实隔离需求时重新评审。
