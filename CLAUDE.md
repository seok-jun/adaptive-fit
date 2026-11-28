## 프로젝트 절차 W

이 작업에 fit 프로젝트 절차를 명시적으로 선택하면 신뢰한 resolver와 사용자 출처 설정으로 `.agentics/default.yaml`의 고정 커밋을 확인하고, 반환된 입구를 읽는다. 제품 요구는 작업 시작 커밋의 `README.md`에서 확인한다.

설계 문서와 검증 이미지를 작성하기 전 W 저장소의 `project/sdd-workflow.md`를 읽는다. SDD는 resolver의 `workflow_dir` 기준 `docs/sdd/{feature}/`에 두고, 제품 이슈·PR에는 W 커밋과 저장소 기준 경로를 남긴다. 관측 보고 요청은 W의 `project/tracker.md`를 따른다.

Fit 이슈의 구현·재개 요청을 받으면 파일을 수정하기 전에 그 이슈에 대한 절차 선택을 확인한다. 이미 선택한 작업이면 그 결정을 재사용한다. 이슈 번호만 있고 선택 기록이 없으면 제품 소유자에게 Fit 절차로 진행할지 한 번 확인한다.

절차를 선택했으면 resolver가 반환한 입구와 보호 규칙을 거쳐 `workflow/skills/implementing-issues/SKILL.md`의 해당 단계를 따른다. 연결 SHA가 맞지 않으면 선택한 버전과 출처부터 조정한다. PR에는 실제로 마친 설계 리뷰와 기능 검증을 구분해 기록한다.
